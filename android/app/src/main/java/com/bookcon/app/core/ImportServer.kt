package com.bookcon.app.core

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.security.SecureRandom

/**
 * Dependency-free HTTP/1.1 upload server (PRD IMP-WIFI): serves an HTML upload form,
 * accepts multipart/form-data POSTs of .pdf/.epub files and hands them to [onSave].
 *
 * Parsing is done in BYTE mode only (never mix a buffered reader with a body stream).
 * Every URL must carry the random per-start token (/t<token>/…) so other devices on a
 * shared network can't push files. Bodies are capped at [MAX_BODY] bytes.
 */
class ImportServer(
    /**
     * Public so the URL handed to the user can be built from the port actually
     * bound, rather than a literal repeated at the call site — which silently
     * drifts the moment anyone constructs the server on another port.
     */
    val port: Int = 8090,
    private val onSave: (file: File, displayName: String) -> Unit,
    /**
     * Reports an upload that was refused, so the phone can say which book failed and
     * why. Without it a rejected file vanished silently: the browser got a 415 and the
     * user saw no change on the phone at all.
     */
    private val onError: (displayName: String, reason: String) -> Unit = { _, _ -> },
) {
    /**
     * URL secret for this session.
     *
     * It used to be a 4-digit number — 10,000 possibilities, and the check was a
     * substring match on the path. On shared or public Wi-Fi any device on the LAN
     * could brute-force it with cheap requests and push files into the library. This is
     * 32 hex characters (128 bits) and is compared as a whole path segment.
     */
    val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    private val _received = MutableStateFlow(0)

    /**
     * Uploads completed so far, as a Flow.
     *
     * It used to be a plain `var receivedCount` incremented from the handler
     * coroutines, which the ViewModel sampled exactly once. The "N books received"
     * line therefore never appeared while the screen was open, so a successful import
     * gave no feedback at all. A StateFlow also makes the increment atomic, which the
     * old non-atomic `+= 1` on a @Volatile Int was not.
     */
    val received: StateFlow<Int> = _received.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var serverSocket: ServerSocket? = null

    /** Result of a start attempt, so the UI can tell success from failure. */
    sealed interface StartResult {
        data object Started : StartResult
        data class Failed(val reason: String) : StartResult
    }

    private val _startResult = MutableStateFlow<StartResult?>(null)
    val startResult: StateFlow<StartResult?> = _startResult.asStateFlow()

    /**
     * Binds the socket on [Dispatchers.IO] and reports the outcome.
     *
     * This used to be fire-and-forget: a `BindException` (something else already on
     * 8090, or entering the screen twice quickly) was only logged, and the caller went
     * on to show "Server running" with a URL that nothing was listening on. The user
     * copied the URL, hit connection refused, and the feature looked broken.
     */
    suspend fun start(): StartResult {
        serverSocket?.let { return StartResult.Started }
        _startResult.value = null
        return try {
            val socket = withContext(Dispatchers.IO) { ServerSocket(port, 8) }
            serverSocket = socket
            scope.launch {
                try {
                    while (!socket.isClosed) {
                        val client = socket.accept()
                        scope.launch { handle(client) }
                    }
                } catch (_: java.io.IOException) {
                    // Socket closed by stop(); expected.
                } catch (t: Throwable) {
                    Log.w(TAG, "import server stopped", t)
                }
            }
            _startResult.value = StartResult.Started
            StartResult.Started
        } catch (t: Throwable) {
            val reason = when (t) {
                is java.net.BindException ->
                    "Port $port is already in use on this device."
                else -> t.message ?: "Could not start the server."
            }
            Log.w(TAG, "import server failed to bind", t)
            serverSocket = null
            _startResult.value = StartResult.Failed(reason)
            StartResult.Failed(reason)
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        // The scope is cancelled here too; it used to be left running, so every
        // visit to the Wi-Fi import screen leaked one CoroutineScope.
        scope.cancel()
    }

    /** Total uploads this session, for callers that only need a one-shot read. */
    fun receivedCount(): Int = _received.value

    /** First site-local IPv4 address, for the UI to display. */
    fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it.isSiteLocalAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress
    }.getOrNull()

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 20_000
                val input = s.getInputStream()

                // 1) Read until CRLFCRLF (end of headers), all in bytes.
                val headBuf = ByteArrayOutputStream()
                val scan = ArrayDeque<Byte>()
                while (true) {
                    val b = input.read()
                    if (b < 0) return
                    headBuf.write(b)
                    scan.addLast(b.toByte())
                    if (scan.size > 4) scan.removeFirst()
                    if (scan == HEAD_END) break
                    if (headBuf.size() > MAX_HEAD) return respond(s, 400, "headers too large")
                }

                // 2) Parse request line + headers from what we already consumed.
                val headText = String(headBuf.toByteArray(), Charsets.ISO_8859_1)
                val lines = headText.split("\r\n")
                val requestLine = lines.firstOrNull().orEmpty()
                val headers = HashMap<String, String>()
                lines.drop(1).forEach { line ->
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }

                val method = requestLine.substringBefore(' ')
                val path = requestLine.removePrefix(method).trim().substringBefore(' ')
                // Exact segment match, not substring: "/t<token>/anything" is the
                // only accepted form, so a longer path cannot smuggle a prefix in.
                if (path.trimEnd('/') != "/t$token") return respond(s, 404, "not found")

                when {
                    method == "GET" -> respondHtml(s, formPage())

                    method == "POST" -> {
                        val length = headers["content-length"]?.toIntOrNull() ?: 0
                        if (length <= 0 || length > MAX_BODY) return respond(s, 400, "bad size")
                        val boundaryToken = headers["content-type"]
                            ?.substringAfter("boundary=", missingDelimiterValue = "")
                            ?.trim()?.removePrefix("\"")?.substringBefore("\"").orEmpty()
                        if (boundaryToken.isBlank()) return respond(s, 400, "no boundary")

                        // 3) Spool exactly [length] body bytes to disk.
                        //
                        // This used to be `ByteArray(length)` followed by
                        // `body.copyOfRange(...)` to lift the payload out — two full
                        // copies of the upload in memory at once, so the advertised
                        // 400 MB ceiling needed ~800 MB of heap and any device with
                        // less than that was OOM-killed mid-upload, with no error on
                        // either end. Streaming keeps peak memory at one small buffer.
                        val spool = File.createTempFile("bcbody", ".bin")
                        try {
                            var off = 0L
                            val buffer = ByteArray(64 * 1024)
                            spool.outputStream().use { out ->
                                while (off < length) {
                                    val want = minOf(buffer.size.toLong(), length - off).toInt()
                                    val n = input.read(buffer, 0, want)
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                    off += n
                                }
                            }
                            if (off < length) return respond(s, 400, "short body")

                            val name = extractFileName(spool)
                                ?: return respond(s, 400, "no file part")
                            val lower = name.lowercase()
                            if (!lower.endsWith(".pdf") && !lower.endsWith(".epub")) {
                                return respond(s, 415, "only pdf or epub")
                            }
                            val payloadSize = payloadRange(spool, boundaryToken)
                                ?: return respond(s, 400, "malformed part")
                            if (payloadSize.second <= payloadSize.first) {
                                return respond(s, 400, "empty file")
                            }
                            // Extension alone proves nothing. A renamed JPEG, a
                            // truncated download or a random text file all sail past
                            // a suffix check and then land in the library marked READY,
                            // where they cannot be opened and give no indication why.
                            // Sniff the actual bytes and reject what is not a book.
                            val claimedPdf = lower.endsWith(".pdf")
                            val actual = sniffFormat(spool, payloadSize)
                            if (actual == null) {
                                onError(name, "is not a PDF or an EPUB")
                                return respond(s, 415, "that file is not a PDF or EPUB")
                            }
                            if (claimedPdf && actual != Format.PDF) {
                                onError(name, "is named .pdf but the contents are not a PDF")
                                return respond(s, 415, "named .pdf but the contents are not a PDF")
                            }

                            val safe = name.sanitizeName()
                            val tmp = File.createTempFile("bcupload", ".bin")
                            copyRange(spool, tmp, payloadSize.first, payloadSize.second)
                            onSave(tmp, safe)
                            _received.value += 1
                            respond(s, 200, "{\"saved\": \"$safe\"}", json = true)
                        } finally {
                            runCatching { spool.delete() }
                        }
                    }

                    else -> respond(s, 405, "method not allowed")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "connection failed", t)
            }
        }
    }

    /** filename="…" out of the multipart part headers (searched in ISO-8859-1 text form). */
    private fun extractFileName(file: File): String? {
        val marker = "filename=\""
        // Part headers sit at the very start of the part, so the first 64 KB is
        // plenty and this never reads the file into memory.
        val head = ByteArray(minOf(file.length(), 64L * 1024).toInt())
        file.inputStream().use { it.readFully(head) }
        val text = String(head, Charsets.ISO_8859_1)
        val i = text.indexOf(marker)
        if (i < 0) return null
        val start = i + marker.length
        val end = text.indexOf('"', start)
        if (end <= start) return null
        return text.substring(start, end)
    }

    /** Container formats the importer accepts, as detected from the bytes. */
    enum class Format { PDF, EPUB }

    /**
     * Identifies the payload by its magic bytes rather than its file name.
     *
     * PDF is `%PDF-`. EPUB is a ZIP whose first entry must be an uncompressed
     * `mimetype` holding `application/epub+zip` (EPUB OCF); `META-INF/container.xml`
     * is accepted as a fallback for archives that break that rule in the wild. An
     * ordinary ZIP that is not an EPUB fails, which is the point.
     */
    /**
     * Public so it can be exercised directly by tests: this is the whole of the
     * "is this really a book?" decision and it deserves to be pinned down.
     *
     * (Internal members are name-mangled in Kotlin, so an internal version was not
     * reachable by reflection.)
     */
    fun sniffFormat(file: File, range: Pair<Long, Long>): Format? = runCatching {
        val length = range.second - range.first
        if (length <= 0) return@runCatching null
        val head = ByteArray(minOf(length, 64L * 1024).toInt())
        file.inputStream().use { input ->
            input.skipFully(range.first)
            input.readFully(head)
        }
        when {
            head.size >= 5 && String(head, 0, 5, Charsets.ISO_8859_1) == "%PDF-" -> Format.PDF
            isZip(head) -> sniffZip(file, range)
            else -> null
        }
    }.getOrNull()

    /**
     * Identifies a ZIP payload by walking its entries.
     *
     * Scanning the raw bytes is not enough: ZIP stores entry NAMES uncompressed but
     * entry DATA deflated, so a spec-conformant EPUB with an uncompressed `mimetype`
     * shows its value in the clear while a common non-conformant one with a deflated
     * `mimetype` does not. Walking the entries handles both, and keeps rejecting an
     * ordinary ZIP that merely has a .epub name.
     */
    private fun sniffZip(file: File, range: Pair<Long, Long>): Format? = runCatching {
        file.inputStream().use { raw ->
            raw.skipFully(range.first)
            ZipInputStream(raw.buffered()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                var sawContainer = false
                var sawMimetype = false
                while (entry != null && entry.name != null) {
                    val name = entry.name
                    if (name.equals("META-INF/container.xml", ignoreCase = true)) sawContainer = true
                    if (name.equals("mimetype", ignoreCase = true)) {
                        sawMimetype = true
                        val declared = zip.readBytes().toString(Charsets.ISO_8859_1)
                        if (declared.contains("application/epub+zip")) return@use Format.EPUB
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                if (sawContainer) Format.EPUB else if (sawMimetype) null else null
            }
        }
    }.getOrNull()

    private fun isZip(head: ByteArray): Boolean =
        head.size >= 4 &&
            head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            head[2] == 3.toByte() && head[3] == 4.toByte()

    /**
     * Byte range of the part's payload: between the header's CRLFCRLF and its
     * closing CRLF--boundary. Returned as [start, endExclusive] rather than as bytes,
     * so the caller can copy the range straight to the destination file.
     */
    private fun payloadRange(file: File, boundary: String): Pair<Long, Long>? {
        val open = "--$boundary".toByteArray(Charsets.ISO_8859_1)
        val partStart = indexOfIn(file, open, 0) ?: return null
        val hdrEnd = indexOfIn(file, HEAD_END_BYTES, partStart + open.size) ?: return null
        val dataStart = hdrEnd + HEAD_END_BYTES.size
        val closeMark = "\r\n--$boundary".toByteArray(Charsets.ISO_8859_1)
        val close = indexOfIn(file, closeMark, dataStart) ?: return null
        return dataStart.toLong() to close.toLong()
    }

    /** Copies [start, endExclusive) from [from] to [to] without buffering the range. */
    private fun copyRange(from: File, to: File, start: Long, endExclusive: Long) {
        from.inputStream().use { input ->
            input.skipFully(start)
            to.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var remaining = endExclusive - start
                while (remaining > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                    remaining -= n
                }
            }
        }
    }

    /** Scanning [from] to the end, returns the first offset of [needle] at or after [from]. */
    private fun indexOfIn(file: File, needle: ByteArray, from: Int): Int? {
        if (needle.isEmpty()) return null
        var found = -1
        file.inputStream().use { input ->
            input.skipFully(from.toLong())
            val window = ByteArray(64 * 1024)
            // Keep the last needle.size-1 bytes so a match spanning two
            // reads is still found. The offset is tracked in absolute terms.
            var carry = ByteArray(0)
            var base = from.toLong()
            while (true) {
                val n = input.read(window)
                if (n <= 0) break
                val chunk = ByteArray(carry.size + n)
                carry.copyInto(chunk)
                window.copyInto(chunk, carry.size, 0, n)
                // The class's own indexOf() returns Int? (not found).
                val idx = indexOf(chunk, needle, 0)
                if (idx != null && idx >= 0) { found = (base - carry.size + idx).toInt(); break }
                val keep = minOf(needle.size - 1, chunk.size)
                carry = chunk.copyOfRange(chunk.size - keep, chunk.size)
                base += n
            }
        }
        return found.takeIf { it >= 0 }
    }

    private fun java.io.InputStream.skipFully(n: Long) {
        var left = n
        while (left > 0) {
            val skipped = skip(left)
            if (skipped > 0) { left -= skipped; continue }
            if (read() < 0) return
            left--
        }
    }

    private fun java.io.InputStream.readFully(dest: ByteArray) {
        var off = 0
        while (off < dest.size) {
            val n = read(dest, off, dest.size - off)
            if (n < 0) break
            off += n
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9 ._()-]"), "_").take(120).ifBlank { "book" }

    private fun String.sanitizeName(): String = sanitize(this)

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int? {
        if (needle.isEmpty() || haystack.size < needle.size) return null
        var i = maxOf(from, 0)
        outer@ while (i <= haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return null
    }

    private fun formPage(): String = """
        <!doctype html><html><head><meta charset='utf-8'>
        <meta name='viewport' content='width=device-width, initial-scale=1'>
        <title>BookCon import</title>
        <style>
          body{background:#11151c;color:#e6e6e6;font-family:sans-serif;display:flex;
               min-height:90vh;align-items:center;justify-content:center;margin:0}
          .card{background:#1b2230;padding:32px;border-radius:16px;text-align:center}
          input[type=file]{color:#e6e6e6;margin:18px 0}
          button{background:#3d5afe;color:#fff;border:none;border-radius:10px;
                 padding:12px 26px;font-size:16px}
          .hint{opacity:.7;font-size:13px;margin-top:14px}
        </style></head>
        <body><div class='card'>
          <h2>Send books to BookCon</h2>
          <form action='/t$token/upload' method='post' enctype='multipart/form-data'>
            <input type='file' name='book' accept='.pdf,.epub' required multiple><br>
            <button type='submit'>Upload</button>
          </form>
          <div class='hint'>PDF or EPUB · up to 400 MB each</div>
        </div></body></html>
    """.trimIndent()

    private fun respondHtml(socket: Socket, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
    }

    private fun respond(socket: Socket, code: Int, message: String, json: Boolean = false) {
        val type = if (json) "application/json" else "text/plain; charset=utf-8"
        val body = message.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code OK\r\nContent-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
    }

    companion object {
        private const val TAG = "ImportServer"
        private const val MAX_BODY = 400L * 1024 * 1024
        private const val MAX_HEAD = 32 * 1024
        private val HEAD_END = listOf<Byte>(13, 10, 13, 10)
        private val HEAD_END_BYTES = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
    }
}
