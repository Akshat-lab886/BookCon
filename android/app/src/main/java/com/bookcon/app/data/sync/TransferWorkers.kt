package com.bookcon.app.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bookcon.app.core.SessionStore
import com.bookcon.app.data.local.BookConDatabase
import com.bookcon.app.data.local.BookEntity
import com.bookcon.app.data.local.DownloadState
import com.bookcon.app.data.local.UploadQueueItem
import com.bookcon.app.data.local.UploadState
import com.bookcon.app.data.remote.ApiProvider
import com.bookcon.app.data.remote.InitiateUploadRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody

/** Enqueues an imported file for background upload (PRD LIB-1: survives app death). */
fun enqueueUpload(context: Context) {
    WorkManager.getInstance(context).enqueueUniqueWork(
        "bookcon-upload",
        ExistingWorkPolicy.APPEND_OR_REPLACE,
        OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build(),
    )
}

/** Downloads a book file for offline reading (PRD SYN-6). */
fun enqueueDownload(context: Context, bookId: String) {
    WorkManager.getInstance(context).enqueueUniqueWork(
        "bookcon-download-$bookId",
        ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder().putString(DownloadWorker.KEY_BOOK_ID, bookId).build())
            .build(),
    )
}

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val db: BookConDatabase,
    private val apiProvider: ApiProvider,
    private val sessions: SessionStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (sessions.current()?.accessToken == null) return Result.success()
        val dao = db.uploadQueueDao()

        var failed = false
        var permanent = false
        for (item in dao.pending()) {
            if (item.attempts >= MAX_ATTEMPTS) {
                permanent = true // poison item: stop retrying it forever
                continue
            }
            try {
                processItem(dao, item)
            } catch (_: Exception) {
                failed = true
                dao.update(item.copy(attempts = item.attempts + 1, state = UploadState.FAILED))
            }
        }
        dao.clearDone()
        return when {
            permanent -> Result.failure() // unblocks the chain; remaining items stay queued
            failed -> Result.retry()
            else -> Result.success()
        }
    }

    private suspend fun processItem(dao: com.bookcon.app.data.local.UploadQueueDao, item: UploadQueueItem) {
        // 1. Stream SAF bytes into app storage exactly once (hash was computed at import).
        val local = ensureStaged(item)

        // 2. initiate-upload — idempotent server-side: a book still "processing"
        //    from an interrupted attempt returns a FRESH upload_required URL for
        //    the same book id; fully-uploaded content returns "duplicate".
        val resp = apiProvider.get().initiateUpload(
            InitiateUploadRequest(
                filename = item.filename,
                sizeBytes = local.length(),
                sha256 = item.sha256,
                contentType = item.contentType,
            ),
        )
        val body = resp.body() ?: throw IOException("initiate-upload failed ${resp.code()}")
        val bookId = body.bookId
        dao.update(item.copy(bookId = bookId))

        when (body.outcome) {
            "duplicate" -> {
                // Already fully on the server; adopt it locally and finish.
                markDone(dao, item.copy(bookId = bookId))
                upsertLocalBook(bookId, local.absolutePath)
                return
            }
            "upload_required" -> Unit
            else -> throw IOException("unexpected initiate outcome: ${body.outcome}")
        }

        // 3. PUT bytes to the signed URL — streamed straight from the staging file.
        val uploadUrl = requireNotNull(body.uploadUrl) { "upload_required without a url" }
        val media = (item.contentType.ifBlank { "application/octet-stream" }).toMediaTypeOrNull()
        val putRequest = okhttp3.Request.Builder()
            .url(uploadUrl)
            .put(local.asRequestBody(media))
            .build()
        okhttpOkClient().newCall(putRequest).execute().use { put ->
            check(put.isSuccessful) { "PUT failed ${put.code}" }
        }

        // 4. complete-upload → triggers metadata extraction.
        val done = apiProvider.get().completeUpload(bookId)
        check(done.isSuccessful) { "complete-upload failed ${done.code()}" }
        markDone(dao, item.copy(bookId = bookId))

        // 5. Optimistic local insert so the library shows it immediately.
        upsertLocalBook(bookId, local.absolutePath)
    }

    /** Copies the SAF uri into filesDir/imports once; subsequent runs reuse the file. */
    private fun ensureStaged(item: UploadQueueItem): File {
        val ext = item.filename.substringAfterLast('.', "bin")
        val local = File(File(appContext.filesDir, "imports"), "${item.sha256}.$ext")
        if (local.exists() && local.length() > 0) return local
        local.parentFile?.mkdirs()
        val uri = android.net.Uri.parse(item.pendingUri)
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            local.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Cannot reopen $uri")
        return local
    }

    private suspend fun markDone(dao: com.bookcon.app.data.local.UploadQueueDao, item: UploadQueueItem) {
        dao.update(item.copy(state = UploadState.DONE))
    }

    /** Optimistic insert from complete-upload response (or minimal placeholder). */
    private suspend fun upsertLocalBook(bookId: String, localPath: String) {
        val session = sessions.current() ?: return
        val detail = apiProvider.get().book(bookId)
        val existing = db.bookDao().get(bookId)
        val entity = detail.body()?.toEntity(session.userId ?: "")
            ?: BookEntity(
                id = bookId, userId = session.userId.orEmpty(), format = "epub", status = "processing",
                title = "Importing…", addedAt = nowIso(), updatedAt = nowIso(), dirty = false,
            )
        // Never clobber local-only state of an already-present row.
        db.bookDao().upsert(
            entity.copy(
                localFile = existing?.localFile ?: localPath,
                downloadState = existing?.downloadState ?: DownloadState.READY,
                lastOpenedAt = existing?.lastOpenedAt,
            ),
        )
    }

    companion object {
        const val MAX_ATTEMPTS = 5

        fun nowIso(): String =
            java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString()
    }
}

private fun okhttpOkClient(): okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder().build()

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val db: BookConDatabase,
    private val apiProvider: ApiProvider,
    private val sessions: SessionStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure()
        val book = db.bookDao().get(bookId) ?: return Result.failure()
        try {
            val resp = apiProvider.get().fileUrl(bookId)
            // 409 (still processing / not uploaded) and 404 are permanent for this run.
            if (resp.code() == 409 || resp.code() == 404) {
                db.bookDao().setLocalFile(bookId, book.localFile, DownloadState.FAILED)
                return Result.failure()
            }
            val fileUrl = resp.body() ?: throw IOException("file-url failed ${resp.code()}")
            val client = okhttp3.OkHttpClient.Builder().build()
            client.newCall(okhttp3.Request.Builder().url(fileUrl.url).build()).execute().use { dl ->
                check(dl.isSuccessful) { "download failed ${dl.code}" }
                val dir = File(applicationContext.filesDir, "books").apply { mkdirs() }
                val target = File(dir, "${book.id.replace("-", "")}.${book.format}")
                dl.body!!.byteStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                db.bookDao().setLocalFile(bookId, target.absolutePath, DownloadState.READY)
            }
            return Result.success()
        } catch (_: Exception) {
            db.bookDao().setLocalFile(bookId, book.localFile, DownloadState.FAILED)
            return if (runAttemptCount >= MAX_DOWNLOAD_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    companion object {
        const val KEY_BOOK_ID = "book_id"
        const val MAX_DOWNLOAD_ATTEMPTS = 5
    }
}
