package com.bookcon.app.ui.importwifi

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookcon.app.core.CoverExtractor
import com.bookcon.app.core.ImportServer
import com.bookcon.app.data.local.BookDao
import com.bookcon.app.data.local.BookEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class WifiImportUiState(
    val running: Boolean = false,
    val url: String? = null,
    val received: Int = 0,
    /** Non-null when the server could not start; surfaced instead of a dead URL. */
    val error: String? = null,
    /** Set when an upload is refused, naming the file and the reason. */
    val lastError: String? = null,
)

/**
 * Drives [ImportServer]: uploads land in files/imports/, get a cover, and are inserted
 * into the library via BookDao so they appear immediately (same flow as adb-import).
 */
@HiltViewModel
class WifiImportViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val bookDao: BookDao,
) : ViewModel() {

    private var server: ImportServer? = null
    private var receivedJob: kotlinx.coroutines.Job? = null

    private val _state = MutableStateFlow(WifiImportUiState())
    val state: StateFlow<WifiImportUiState> = _state

    fun startServer() {
        if (server != null) return
        val imports = File(appContext.filesDir, "imports").apply { mkdirs() }
        val s = ImportServer(
            port = 8090,
            onError = { name, reason ->
                _state.update { it.copy(lastError = "Rejected \u201c$name\u201d \u2014 $reason") }
            },
            onSave = { tmpFile, displayName ->
            // Called on the server's IO dispatcher.
            runCatching {
                val dest = File(imports, "${System.currentTimeMillis()}-$displayName")
                if (!tmpFile.renameTo(dest)) {
                    tmpFile.copyTo(dest, overwrite = true)
                    tmpFile.delete()
                }
                // Trust the bytes, not the file name: the server already sniffed them.
                val isPdf = com.bookcon.app.reader.PdfBook.looksLikePdf(dest)
                val nowIso = java.time.Instant.now().toString()
                val bookId = "wifi-${System.currentTimeMillis()}"
                val baseTitle = displayName.substringBeforeLast('.')
                val entity = BookEntity(
                    id = bookId,
                    userId = "local",
                    format = if (isPdf) "PDF" else "EPUB",
                    status = "READY",
                    statusMessage = null,
                    title = baseTitle,
                    authors = listOf("Unknown"),
                    fileSizeBytes = dest.length(),
                    addedAt = nowIso,
                    updatedAt = nowIso,
                    dirty = true,
                    localFile = dest.absolutePath,
                )
                // Not runBlocking: that blocked the HTTP response thread through a
                // database write AND a cover render, so a large PDF froze the request
                // handler for seconds. onSave is a plain function type with no scope
                // receiver, so the work is dispatched onto the ViewModel's own scope.
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        bookDao.upsert(entity)
                        CoverExtractor.ensureCover(appContext, bookId, entity.format, dest.absolutePath)
                    }
                }
            }.onFailure {
                android.util.Log.w("WifiImport", "save failed", it)
                _state.update { st -> st.copy(lastError = "Couldn't save \u201c$displayName\u201d") }
            }
            },
        )
        viewModelScope.launch {
            // start() reports the bind outcome. It used to be fire-and-forget with a
            // swallowed BindException, so a port clash produced a "Server running"
            // screen with a URL nothing was listening on.
            when (val result = s.start()) {
                is ImportServer.StartResult.Failed -> {
                    _state.update {
                        it.copy(running = false, url = null, error = result.reason)
                    }
                    return@launch
                }
                ImportServer.StartResult.Started -> Unit
            }
            server = s
            val ip = withContext(Dispatchers.IO) { s.localIp() }
            _state.update {
                it.copy(
                    running = true,
                    error = null,
                    // Built from the bound port, not a repeated literal.
                    url = ip?.let { host -> "http://$host:${s.port}/t${s.token}" },
                )
            }
            // The count has to follow the server, not be sampled once at start, or the
            // confirmation line never appears and a successful import gives no feedback.
            //
            // Held so stopServer can cancel it. The flow is a StateFlow and never
            // completes, so without this each start left a collector running for the
            // life of the ViewModel, all of them still writing into the same state.
            receivedJob = viewModelScope.launch {
                s.received.collect { count ->
                    _state.update { it.copy(received = count) }
                }
            }
        }
    }

    fun stopServer() {
        receivedJob?.cancel()
        receivedJob = null
        server?.stop()
        server = null
        // The label says "received this session", so the count has to start over
        // with the session. It did not: the previous session's total was still on
        // screen the moment a new server started, so a fresh import that received
        // nothing looked like it had already worked.
        _state.update { it.copy(running = false, url = null, received = 0) }
    }

    override fun onCleared() {
        stopServer()
        super.onCleared()
    }
}
