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

    private val _state = MutableStateFlow(WifiImportUiState())
    val state: StateFlow<WifiImportUiState> = _state

    fun startServer() {
        if (server != null) return
        val imports = File(appContext.filesDir, "imports").apply { mkdirs() }
        val s = ImportServer(port = 8090) { tmpFile, displayName ->
            // Called on the server's IO dispatcher.
            runCatching {
                val dest = File(imports, "${System.currentTimeMillis()}-$displayName")
                if (!tmpFile.renameTo(dest)) {
                    tmpFile.copyTo(dest, overwrite = true)
                    tmpFile.delete()
                }
                val isPdf = displayName.lowercase().endsWith(".pdf")
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
                kotlinx.coroutines.runBlocking {
                    bookDao.upsert(entity)
                    CoverExtractor.ensureCover(appContext, bookId, entity.format, dest.absolutePath)
                }
            }.onFailure {
                android.util.Log.w("WifiImport", "save failed", it)
            }
        }
        s.start()
        server = s
        viewModelScope.launch(Dispatchers.IO) {
            val ip = s.localIp()
            _state.update {
                it.copy(
                    running = true,
                    url = ip?.let { "http://$ip:${8090}/t${s.token}" },
                    received = s.receivedCount,
                )
            }
        }
    }

    fun stopServer() {
        server?.stop()
        server = null
        _state.update { it.copy(running = false, url = null) }
    }

    override fun onCleared() {
        stopServer()
        super.onCleared()
    }
}
