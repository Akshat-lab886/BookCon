package com.bookcon.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books")
    suspend fun all(): List<BookEntity>

    @Query(
        """
        SELECT * FROM books
        WHERE deletedAt IS NULL
          AND (:q IS NULL OR title LIKE '%' || :q || '%' OR description LIKE '%' || :q || '%')
        ORDER BY
            CASE WHEN :sort = 'title' THEN title END COLLATE NOCASE ASC,
            CASE WHEN :sort = 'recent' THEN updatedAt END DESC,
            CASE WHEN :sort = 'added' THEN addedAt END DESC
        """
    )
    fun observeLibrary(q: String?, sort: String): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE deletedAt IS NULL AND lastOpenedAt IS NOT NULL ORDER BY lastOpenedAt DESC LIMIT 10")
    fun observeContinueReading(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeBook(id: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: String): BookEntity?

    @Query("SELECT * FROM books WHERE dirty = 1")
    suspend fun dirty(): List<BookEntity>

    @Query("SELECT * FROM books WHERE pinnedOffline = 1 AND localFile IS NOT NULL")
    suspend fun pinned(): List<BookEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(books: List<BookEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: BookEntity)

    @Query("UPDATE books SET dirty = 0 WHERE id IN (:ids)")
    suspend fun clearDirty(ids: List<String>)

    /** Apply pulled server rows without marking them dirty (TRD §3.2 pull step 3).
     *  Locally-dirty rows stay untouched until push resolves LWW. */
    @Transaction
    suspend fun applyPulled(books: List<BookEntity>) {
        val dirtyIds = dirty().map { it.id }.toSet()
        upsertAll(books.filter { it.id !in dirtyIds })
    }

    @Query("UPDATE books SET localFile = :path, downloadState = :state WHERE id = :id")
    suspend fun setLocalFile(id: String, path: String?, state: Int)

    @Query("UPDATE books SET lastOpenedAt = :ts WHERE id = :id")
    suspend fun touchOpened(id: String, ts: Long)
}

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM annotations WHERE deletedAt IS NULL AND bookId = :bookId ORDER BY updatedAt ASC")
    fun observeForBook(bookId: String): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotations WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotations WHERE bookId = :bookId AND deletedAt IS NULL")
    suspend fun forBook(bookId: String): List<AnnotationEntity>

    @Query("SELECT * FROM annotations WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AnnotationEntity?

    @Query("SELECT * FROM annotations")
    suspend fun allRaw(): List<AnnotationEntity>

    @Query("SELECT * FROM annotations WHERE dirty = 1")
    suspend fun dirty(): List<AnnotationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<AnnotationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: AnnotationEntity)

    @Query("UPDATE annotations SET deletedAt = :tombstone, updatedAt = :now, dirty = 1 WHERE id = :id")
    suspend fun tombstone(id: String, tombstone: String, now: String)

    @Query("UPDATE annotations SET dirty = 0 WHERE id IN (:ids)")
    suspend fun clearDirty(ids: List<String>)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks")
    suspend fun all(): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE deletedAt IS NULL AND bookId = :bookId ORDER BY updatedAt ASC")
    fun observeForBook(bookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE dirty = 1")
    suspend fun dirty(): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<BookmarkEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: BookmarkEntity)

    @Query("UPDATE bookmarks SET deletedAt = :tombstone, updatedAt = :now, dirty = 1 WHERE id = :id")
    suspend fun tombstone(id: String, tombstone: String, now: String)

    @Query("UPDATE bookmarks SET dirty = 0 WHERE id IN (:ids)")
    suspend fun clearDirty(ids: List<String>)
}

@Dao
interface PositionDao {
    @Query("SELECT * FROM positions WHERE bookId = :bookId")
    fun observe(bookId: String): Flow<PositionEntity?>

    @Query("SELECT * FROM positions")
    suspend fun all(): List<PositionEntity>

    @Query("SELECT * FROM positions WHERE dirty = 1")
    suspend fun dirty(): List<PositionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: PositionEntity)

    /** LWW guard: never let pulled rows regress or clobber pending-push state.
     *  Timestamps are parsed before comparing — client stamps end in "Z" while
     *  server stamps end in "+00:00", so raw string comparison misorders them. */
    suspend fun applyLww(item: PositionEntity) {
        val current = all().firstOrNull { it.bookId == item.bookId }
        if (current == null || (!current.dirty && isNewer(item.updatedAt, current.updatedAt))) {
            upsert(item)
        }
    }

private fun isNewer(candidate: String, stored: String): Boolean {
    fun parse(value: String): java.time.OffsetDateTime? = try {
        java.time.OffsetDateTime.parse(value)
    } catch (_: Exception) {
        try {
            java.time.OffsetDateTime.ofInstant(java.time.Instant.parse(value), java.time.ZoneOffset.UTC)
        } catch (_: Exception) {
            null
        }
    }
    val a = parse(candidate) ?: return candidate > stored
    val b = parse(stored) ?: return true
    return a.isAfter(b)
}
}

@Dao
interface OrganizeDao {
    @Query("SELECT * FROM shelves WHERE deletedAt IS NULL ORDER BY sortPosition ASC")
    fun observeShelves(): Flow<List<ShelfEntity>>

    @Query("SELECT * FROM tags WHERE deletedAt IS NULL ORDER BY name ASC")
    fun observeTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM series WHERE deletedAt IS NULL ORDER BY name ASC")
    fun observeSeries(): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM shelves WHERE dirty = 1")
    suspend fun dirtyShelves(): List<ShelfEntity>

    @Query("SELECT * FROM tags WHERE dirty = 1")
    suspend fun dirtyTags(): List<TagEntity>

    @Query("SELECT * FROM series WHERE dirty = 1")
    suspend fun dirtySeries(): List<SeriesEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertShelves(items: List<ShelfEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTags(items: List<TagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeries(items: List<SeriesEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertShelf(item: ShelfEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTag(item: TagEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeries(item: SeriesEntity)
}

@Dao
interface SyncCursorDao {
    @Query("SELECT * FROM sync_cursors WHERE entity = :entity")
    suspend fun get(entity: String): SyncCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(cursor: SyncCursorEntity)
}

@Dao
interface UploadQueueDao {
    @Query("SELECT * FROM upload_queue WHERE state != :doneState ORDER BY rowId ASC")
    suspend fun pending(doneState: Int = UploadState.DONE): List<UploadQueueItem>

    @Query("SELECT * FROM upload_queue ORDER BY rowId ASC")
    fun observeAll(): Flow<List<UploadQueueItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(item: UploadQueueItem): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(item: UploadQueueItem)

    @Query("DELETE FROM upload_queue WHERE state = :doneState")
    suspend fun clearDone(doneState: Int = UploadState.DONE)

    @Query("SELECT COUNT(*) FROM upload_queue WHERE state != :doneState")
    suspend fun pendingCount(doneState: Int = UploadState.DONE): Int
}
