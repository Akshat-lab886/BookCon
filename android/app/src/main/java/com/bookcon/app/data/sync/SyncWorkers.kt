package com.bookcon.app.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bookcon.app.core.SessionStore
import com.bookcon.app.data.local.AnnotationEntity
import com.bookcon.app.data.local.BookConDatabase
import com.bookcon.app.data.local.BookmarkEntity
import com.bookcon.app.data.local.PositionEntity
import com.bookcon.app.data.local.ShelfEntity
import com.bookcon.app.data.local.SeriesEntity
import com.bookcon.app.data.local.SyncCursorEntity
import com.bookcon.app.data.local.TagEntity
import com.bookcon.app.data.remote.ApiProvider
import com.bookcon.app.data.remote.AnnotationDto
import com.bookcon.app.data.remote.BookDto
import com.bookcon.app.data.remote.BookmarkDto
import com.bookcon.app.data.remote.LocatorDto
import com.bookcon.app.data.remote.PositionDto
import com.bookcon.app.data.remote.ShelfDto
import com.bookcon.app.data.remote.SeriesDto
import com.bookcon.app.data.remote.SyncPullRequest
import com.bookcon.app.data.remote.SyncPushRequest
import com.bookcon.app.data.remote.TagDto
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Sync engine per TRD §3.2:
 *  - PushWorker: drains dirty rows in batches → /sync/push; clears dirty flags on accept,
 *    adopts authoritative payloads otherwise. Retries are idempotent.
 *  - PullWorker: per-entity watermarked cursors → /sync/pull; applies inside Room txns
 *    without setting dirty flags.
 */
object SyncScheduler {
    fun schedulePeriodic(context: Context) {
        val wm = WorkManager.getInstance(context)
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        // Push local edits FIRST, then pull — otherwise a pull can arrive while
        // edits are still dirty (they are guarded, but they would lag behind).
        wm.enqueueUniquePeriodicWork(
            "bookcon-periodic-sync",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PushWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build(),
        )
        wm.enqueueUniquePeriodicWork(
            "bookcon-periodic-pull",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PullWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build(),
        )
    }

    fun requestSync(context: Context) {
        val wm = WorkManager.getInstance(context)
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        wm.beginUniqueWork("bookcon-sync-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<PushWorker>().setConstraints(constraints).build())
            .then(OneTimeWorkRequestBuilder<PullWorker>().setConstraints(constraints).build())
            .enqueue()
    }
}

@HiltWorker
class PushWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val db: BookConDatabase,
    private val apiProvider: ApiProvider,
    private val sessions: SessionStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!sessions.current().let { it?.accessToken != null }) return Result.success()

        return try {
            pushAnnotations()
            pushBookmarks()
            pushPositions()
            pushShelves()
            pushTags()
            pushSeries()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private suspend fun pushAnnotations() {
        val dirty = db.annotationDao().dirty()
        if (dirty.isEmpty()) return
        val resp = apiProvider.get().syncPush(SyncPushRequest(annotations = dirty.map { it.toDto() }))
        if (!resp.isSuccessful) throw java.io.IOException("push annotations ${resp.code()}")
        val body = resp.body() ?: return
        val accepted = body.accepted["annotations"].orEmpty().toSet()
        db.annotationDao().clearDirty(dirty.filter { it.id in accepted }.map { it.id })
        // LWW-ignored rows: the server returned the authoritative version —
        // adopt it locally and clear the flag (TRD §3.2 step 4).
        adoptAuthoritative(body.authoritative["annotations"]) { rows ->
            db.annotationDao().upsertAll(
                rows.mapNotNull { a ->
                    val id = asString(a, "id") ?: return@mapNotNull null
                    com.bookcon.app.data.local.AnnotationEntity(
                        id = id,
                        bookId = asString(a, "book_id").orEmpty(),
                        type = asString(a, "type") ?: "highlight",
                        locatorJson = rawJson(a["locator"]),
                        color = asString(a, "color") ?: "yellow",
                        note = asString(a, "note").orEmpty(),
                        annotationTags = stringList(a["annotation_tags"]),
                        excerpt = asString(a, "excerpt").orEmpty(),
                        createdAt = asString(a, "created_at") ?: nowIso(),
                        updatedAt = asString(a, "updated_at") ?: nowIso(),
                        deletedAt = asString(a, "deleted_at"),
                        dirty = false,
                    )
                },
            )
        }
    }

    private suspend fun pushBookmarks() {
        val dirty = db.bookmarkDao().dirty()
        if (dirty.isEmpty()) return
        val resp = apiProvider.get().syncPush(SyncPushRequest(bookmarks = dirty.map { it.toDto() }))
        if (!resp.isSuccessful) throw java.io.IOException("push bookmarks ${resp.code()}")
        val body = resp.body() ?: return
        val accepted = body.accepted["bookmarks"].orEmpty().toSet()
        db.bookmarkDao().clearDirty(dirty.filter { it.id in accepted }.map { it.id })
        adoptAuthoritative(body.authoritative["bookmarks"]) { rows ->
            db.bookmarkDao().upsertAll(
                rows.mapNotNull { b ->
                    val id = asString(b, "id") ?: return@mapNotNull null
                    com.bookcon.app.data.local.BookmarkEntity(
                        id = id,
                        bookId = asString(b, "book_id").orEmpty(),
                        locatorJson = rawJson(b["locator"]),
                        label = asString(b, "label").orEmpty(),
                        createdAt = asString(b, "created_at") ?: nowIso(),
                        updatedAt = asString(b, "updated_at") ?: nowIso(),
                        deletedAt = asString(b, "deleted_at"),
                        dirty = false,
                    )
                },
            )
        }
    }

    private suspend fun pushPositions() {
        val dirty = db.positionDao().dirty()
        if (dirty.isEmpty()) return
        val resp = apiProvider.get().syncPush(SyncPushRequest(positions = dirty.map { it.toDto() }))
        if (!resp.isSuccessful) throw java.io.IOException("push positions ${resp.code()}")
        val body = resp.body() ?: return
        val accepted = body.accepted["positions"].orEmpty().toSet()
        dirty.filter { it.bookId in accepted }.forEach { db.positionDao().upsert(it.copy(dirty = false)) }
        // LWW-ignored positions: server row wins → overwrite local and clear dirty.
        body.authoritative["positions"].orEmpty().forEach { p ->
            val bookId = asString(p, "book_id") ?: return@forEach
            // Never clobber a locally-dirty position; otherwise adopt the server's.
            if (positionById(bookId)?.dirty == true) return@forEach
            db.positionDao().upsert(
                com.bookcon.app.data.local.PositionEntity(
                    bookId = bookId,
                    locatorJson = rawJson(p["locator"]),
                    progressPercent = jsonDouble(p, "progress_percent"),
                    updatedAt = asString(p, "updated_at") ?: nowIso(),
                    dirty = false,
                ),
            )
        }
    }

    private suspend fun pushShelves() {
        val dirty = db.organizeDao().dirtyShelves()
        if (dirty.isEmpty()) return
        val resp = apiProvider.get().syncPush(SyncPushRequest(shelves = dirty.map { it.toDto() }))
        if (!resp.isSuccessful) throw java.io.IOException("push shelves ${resp.code()}")
        val body = resp.body() ?: return
        val accepted = body.accepted["shelves"].orEmpty().toSet()
        db.organizeDao().upsertShelves(dirty.filter { it.id in accepted }.map { it.copy(dirty = false) })
        // LWW-ignored: adopt the authoritative name/order from the server.
        adoptAuthoritative(body.authoritative["shelves"]) { rows ->
            db.organizeDao().upsertShelves(
                rows.mapNotNull {
                    val id = asString(it, "id") ?: return@mapNotNull null
                    com.bookcon.app.data.local.ShelfEntity(
                        id,
                        asString(it, "name").orEmpty(),
                        jsonLong(it, "sort_position"),
                        asString(it, "updated_at") ?: nowIso(),
                        asString(it, "deleted_at"),
                    )
                },
            )
        }
    }

    private suspend fun pushTags() {
        val dirty = db.organizeDao().dirtyTags()
        if (dirty.isEmpty()) return
        val resp = apiProvider.get().syncPush(SyncPushRequest(tags = dirty.map { it.toDto() }))
        if (!resp.isSuccessful) throw java.io.IOException("push tags ${resp.code()}")
        val body = resp.body() ?: return
        val accepted = body.accepted["tags"].orEmpty().toSet()
        db.organizeDao().upsertTags(dirty.filter { it.id in accepted }.map { it.copy(dirty = false) })
        // LWW-ignored: adopt the authoritative name/order from the server.
        adoptAuthoritative(body.authoritative["tags"]) { rows ->
            db.organizeDao().upsertTags(
                rows.mapNotNull {
                    val id = asString(it, "id") ?: return@mapNotNull null
                    com.bookcon.app.data.local.TagEntity(
                        id,
                        asString(it, "name").orEmpty(),
                        asString(it, "updated_at") ?: nowIso(),
                        asString(it, "deleted_at"),
                    )
                },
            )
        }
    }

    private suspend fun pushSeries() {
        val dirty = db.organizeDao().dirtySeries()
        if (dirty.isEmpty()) return
        val resp = apiProvider.get().syncPush(SyncPushRequest(series = dirty.map { it.toDto() }))
        if (!resp.isSuccessful) throw java.io.IOException("push series ${resp.code()}")
        val body = resp.body() ?: return
        val accepted = body.accepted["series"].orEmpty().toSet()
        db.organizeDao().upsertSeries(dirty.filter { it.id in accepted }.map { it.copy(dirty = false) })
        // LWW-ignored: adopt the authoritative name/order from the server.
        adoptAuthoritative(body.authoritative["series"]) { rows ->
            db.organizeDao().upsertSeries(
                rows.mapNotNull {
                    val id = asString(it, "id") ?: return@mapNotNull null
                    com.bookcon.app.data.local.SeriesEntity(
                        id,
                        asString(it, "name").orEmpty(),
                        asString(it, "updated_at") ?: nowIso(),
                        asString(it, "deleted_at"),
                    )
                },
            )
        }
    }


    private suspend fun adoptAuthoritative(
        rows: List<kotlinx.serialization.json.JsonObject>?,
        apply: suspend (List<kotlinx.serialization.json.JsonObject>) -> Unit,
    ) {
        if (!rows.isNullOrEmpty()) apply(rows)
    }

    private fun asString(obj: kotlinx.serialization.json.JsonObject, key: String): String? =
        (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { !it.isString || it.content != "null" }?.content

    private fun rawJson(element: kotlinx.serialization.json.JsonElement?): String =
        element?.toString() ?: "{}"

    private fun stringList(element: kotlinx.serialization.json.JsonElement?): List<String> =
        (element as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: emptyList()

    private fun jsonLong(obj: kotlinx.serialization.json.JsonObject, key: String): Long =
        (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L

    private fun jsonDouble(obj: kotlinx.serialization.json.JsonObject, key: String): Double? =
        (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()

    private fun nowIso(): String =
        java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString()

    private suspend fun positionById(bookId: String): com.bookcon.app.data.local.PositionEntity? =
        db.positionDao().all().firstOrNull { it.bookId == bookId }

    private suspend fun pullOnce(): Boolean =
        runPullOnce(db, apiProvider, sessions)
}

/** Entities synced through /sync (order defines pull priority; keys match the server registry). */
internal val SYNC_ENTITY_KEYS = listOf(
    "books", "annotations", "bookmarks", "positions", "shelves", "tags", "series",
)

/**
 * One full incremental pull: send per-entity watermarks, merge authoritative rows into Room,
 * persist the returned cursors. Returns true when more pages remain.
 */
internal suspend fun runPullOnce(
    db: BookConDatabase,
    apiProvider: ApiProvider,
    sessions: SessionStore,
): Boolean {
    val userId = sessions.current()?.userId ?: return false
    val cursors = SYNC_ENTITY_KEYS.associateWith { entity ->
        db.syncCursorDao().get(entity)?.watermark
    }.filterValues { it != null }.mapValues { requireNotNull(it.value) }

    val resp = apiProvider.get().syncPull(SyncPullRequest(cursors = cursors))
    if (!resp.isSuccessful) throw java.io.IOException("pull failed ${resp.code()}")
    val body = resp.body() ?: return false
    db.applyMerged(body, userId)

    body.cursors.forEach { (entity, watermark) ->
        db.syncCursorDao().put(SyncCursorEntity(entity, watermark))
    }
    return body.hasMore
}

/** Pull-only worker for periodic background sync (push happens via [PushWorker]). */
class PullWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val db: BookConDatabase,
    private val apiProvider: ApiProvider,
    private val sessions: SessionStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        runPullOnce(db, apiProvider, sessions)
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}

/** Mapping helpers DTO ↔ Entity. */
fun AnnotationEntity.toDto() = AnnotationDto(
    id = id, bookId = bookId, type = type, locator = locatorJson.toLocator(), color = color,
    note = note, annotationTags = annotationTags, excerpt = excerpt,
    createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt,
)

fun BookmarkEntity.toDto() = BookmarkDto(
    id = id, bookId = bookId, locator = locatorJson.toLocator(), label = label,
    createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt,
)

fun PositionEntity.toDto() = PositionDto(bookId = bookId, locator = locatorJson.toLocator(),
    progressPercent = progressPercent, updatedAt = updatedAt)

fun ShelfEntity.toDto() = ShelfDto(id = id, name = name, sortPosition = sortPosition, updatedAt = updatedAt, deletedAt = deletedAt)

fun TagEntity.toDto() = TagDto(id = id, name = name, updatedAt = updatedAt, deletedAt = deletedAt)

fun SeriesEntity.toDto() = SeriesDto(id = id, name = name, updatedAt = updatedAt, deletedAt = deletedAt)

private fun String.toLocator(): LocatorDto =
    kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false }
        .decodeFromString(LocatorDto.serializer(), this)

suspend fun BookConDatabase.applyMerged(
    body: com.bookcon.app.data.remote.SyncPullResponse,
    userId: String,
) {
    // H-2 guard: rows with pending local edits (dirty=1) are NEVER overwritten by
    // a pull — the push has not happened yet, so the server copy is stale and
    // applying it would silently destroy the local edit (or resurrect a deletion).
    val dirtyAnn = annotationDao().dirty().map { it.id }.toSet()
    annotationDao().upsertAll(
        body.annotations
            .filter { it.id !in dirtyAnn }
            .map {
                AnnotationEntity(
                    id = it.id, bookId = it.bookId, type = it.type,
                    locatorJson = kotlinx.serialization.json.Json.encodeToString(LocatorDto.serializer(), it.locator),
                    color = it.color, note = it.note, annotationTags = it.annotationTags, excerpt = it.excerpt,
                    createdAt = it.createdAt, updatedAt = it.updatedAt, deletedAt = it.deletedAt, dirty = false,
                )
            },
    )
    val dirtyBm = bookmarkDao().dirty().map { it.id }.toSet()
    bookmarkDao().upsertAll(
        body.bookmarks
            .filter { it.id !in dirtyBm }
            .map {
                BookmarkEntity(
                    id = it.id, bookId = it.bookId,
                    locatorJson = kotlinx.serialization.json.Json.encodeToString(LocatorDto.serializer(), it.locator),
                    label = it.label, createdAt = it.createdAt, updatedAt = it.updatedAt,
                    deletedAt = it.deletedAt, dirty = false,
                )
            },
    )
    body.positions.forEach {
        positionDao().applyLww(
            PositionEntity(
                bookId = it.bookId,
                locatorJson = kotlinx.serialization.json.Json.encodeToString(LocatorDto.serializer(), it.locator),
                progressPercent = it.progressPercent, updatedAt = it.updatedAt, dirty = false,
            ),
        )
    }
    // Shelves/tags/series carry no per-row content a user edits offline except
    // name/order — same dirty-guard applies.
    val dirtyShelf = organizeDao().dirtyShelves().map { it.id }.toSet()
    organizeDao().upsertShelves(
        body.shelves.filter { it.id !in dirtyShelf }
            .map { ShelfEntity(it.id, it.name, it.sortPosition, it.updatedAt, it.deletedAt) },
    )
    val dirtyTag = organizeDao().dirtyTags().map { it.id }.toSet()
    organizeDao().upsertTags(
        body.tags.filter { it.id !in dirtyTag }
            .map { TagEntity(it.id, it.name, it.updatedAt, it.deletedAt) },
    )
    val dirtySeries = organizeDao().dirtySeries().map { it.id }.toSet()
    organizeDao().upsertSeries(
        body.series.filter { it.id !in dirtySeries }
            .map { SeriesEntity(it.id, it.name, it.updatedAt, it.deletedAt) },
    )
    // Books: preserve local-only columns (download state, file path, open time)
    // that the DTO does not carry — @Insert(REPLACE) would otherwise wipe them.
    bookDao().applyPulled(body.books.map { dto ->
        val incoming = dto.toEntity(userId)
        val existing = bookDao().get(dto.id)
        if (existing == null) incoming else incoming.copy(
            localFile = existing.localFile,
            pinnedOffline = existing.pinnedOffline,
            downloadState = existing.downloadState,
            lastOpenedAt = existing.lastOpenedAt,
        )
    })
}

fun BookDto.toEntity(userId: String) = com.bookcon.app.data.local.BookEntity(
    id = id, userId = userId, format = format, status = status, statusMessage = statusMessage,
    title = title, authors = authors, description = description, language = language,
    publisher = publisher, publishedDate = publishedDate, seriesId = seriesId, seriesIndex = seriesIndex,
    coverUrl = coverUrl, fileSizeBytes = fileSizeBytes, pageCount = pageCount, wordCount = wordCount,
    tagIds = tagIds, shelfIds = shelfIds, addedAt = addedAt, updatedAt = updatedAt, deletedAt = deletedAt,
    dirty = false,
)
