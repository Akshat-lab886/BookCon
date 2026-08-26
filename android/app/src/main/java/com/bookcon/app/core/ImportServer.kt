package com.bookcon.app.core

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
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
    private val port: Int = 8090,
    private val onSave: (file: File, displayName: String) -> Unit,
) {
    val token: String = "%04d".format(SecureRandom().nextInt(10_000))

    @Volatile
    var receivedCount: Int = 0
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (serverSocket != null) return
        scope.launch {
            runCatching {
                ServerSocket(port, 8).apply { serverSocket = this }
                while (serverSocket?.isClosed == false) {
                    val client = serverSocket!!.accept()
                    scope.launch { handle(client) }
                }
            }.onFailure { Log.w(TAG, "import server stopped", it) }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

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
                if (!path.contains("/t$token")) return respond(s, 404, "not found")

                when {
                    method == "GET" -> respondHtml(s, formPage())

                    method == "POST" -> {
                        val length = headers["content-length"]?.toIntOrNull() ?: 0
                        if (length <= 0 || length > MAX_BODY) return respond(s, 400, "bad size")
                        val boundaryToken = headers["content-type"]
                            ?.substringAfter("boundary=", missingDelimiterValue = "")
                            ?.trim()?.removePrefix("\"")?.substringBefore("\"").orEmpty()
                        if (boundaryToken.isBlank()) return respond(s, 400, "no boundary")

                        // 3) Read exactly [length] body bytes (head reader never touched them).
                        val body = ByteArray(length)
                        var off = 0
                        while (off < length) {
                            val n = input.read(body, off, length - off)
                            if (n < 0) break
                            off += n
                        }
                        if (off < length) return respond(s, 400, "short body")

                        val name = extractFileName(body) ?: return respond(s, 400, "no file part")
                        val lower = name.lowercase()
                        if (!lower.endsWith(".pdf") && !lower.endsWith(".epub")) {
                            return respond(s, 415, "only pdf or epub")
                        }
                        val payload = extractPayload(body, boundaryToken)
                            ?: return respond(s, 400, "malformed part")
                        if (payload.isEmpty()) return respond(s, 400, "empty file")

                        val safe = name.sanitizeName()
                        val tmp = File.createTempFile("bcupload", ".bin")
                        tmp.writeBytes(payload)
                        onSave(tmp, safe)
                        receivedCount += 1
                        respond(s, 200, "{\"saved\": \"$safe\"}", json = true)
                    }

                    else -> respond(s, 405, "method not allowed")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "connection failed", t)
            }
        }
    }

    /** filename="…" out of the multipart part headers (searched in ISO-8859-1 text form). */
    private fun extractFileName(body: ByteArray): String? {
        val marker = "filename=\""
        val text = String(body, 0, minOf(body.size, 64 * 1024), Charsets.ISO_8859_1)
        val i = text.indexOf(marker)
        if (i < 0) return null
        val start = i + marker.length
        val end = text.indexOf('"', start)
        if (end <= start) return null
        return text.substring(start, end)
    }

    /** Payload between the part's CRLFCRLF and its closing CRLF--boundary. */
    private fun extractPayload(body: ByteArray, boundary: String): ByteArray? {
        val open = "--$boundary".toByteArray(Charsets.ISO_8859_1)
        val partStart = indexOf(body, open, 0) ?: return null
        val hdrEnd = indexOf(body, HEAD_END_BYTES, partStart + open.size) ?: return null
        val dataStart = hdrEnd + HEAD_END_BYTES.size
        val closeMark = "\r\n--$boundary".toByteArray(Charsets.ISO_8859_1)
        val close = indexOf(body, closeMark, dataStart) ?: return null
        return body.copyOfRange(dataStart, close)
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
