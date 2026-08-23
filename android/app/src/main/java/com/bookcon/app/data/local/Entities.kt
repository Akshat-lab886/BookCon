package com.bookcon.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/**
 * Room entities mirror server rows (TRD §2.2) plus local-only sync columns:
 *  - `dirty`  : local change pending push (TRD §3.2 step 1)
 *  - tombstones via `deletedAt` to prevent resurrection (PRD SYN-3)
 *  - `localFile` path when the book is downloaded for offline use (SYN-6)
 */

@Entity(
    tableName = "books",
    indices = [Index("updatedAt"), Index("userId")],
)
data class BookEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val format: String,
    val status: String,
    val statusMessage: String? = null,
    val title: String,
    val authors: List<String> = emptyList(),
    val description: String = "",
    val language: String? = null,
    val publisher: String? = null,
    val publishedDate: String? = null,
    val seriesId: String? = null,
    val seriesIndex: Double? = null,
    val coverUrl: String? = null,
    val fileSizeBytes: Long? = null,
    val pageCount: Int? = null,
    val wordCount: Int? = null,
    val tagIds: List<String> = emptyList(),
    val shelfIds: List<String> = emptyList(),
    val addedAt: String,
    val updatedAt: String,
    val deletedAt: String? = null,
    // local-only
    val dirty: Boolean = false,
    val localFile: String? = null,   // absolute path of downloaded file
    val pinnedOffline: Boolean = false,
    val downloadState: Int = DownloadState.NONE, // NONE|DOWNLOADING|READY|FAILED
    val lastOpenedAt: Long? = null,
)

object DownloadState {
    const val NONE = 0
    const val DOWNLOADING = 1
    const val READY = 2
    const val FAILED = 3
}

@Entity(tableName = "annotations", indices = [Index("bookId"), Index("updatedAt")])
data class AnnotationEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val type: String,
    /** Readium Locator serialized as JSON string (TRD §2.3). */
    val locatorJson: String,
    val color: String = "yellow",
    val note: String = "",
    val annotationTags: List<String> = emptyList(),
    val excerpt: String = "",
    val createdAt: String?,
    val updatedAt: String,
    val deletedAt: String? = null,
    val dirty: Boolean = false,
)

@Entity(tableName = "bookmarks", indices = [Index("bookId"), Index("updatedAt")])
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val locatorJson: String,
    val label: String = "",
    val createdAt: String?,
    val updatedAt: String,
    val deletedAt: String? = null,
    val dirty: Boolean = false,
)

@Entity(tableName = "positions")
data class PositionEntity(
    @PrimaryKey val bookId: String,
    /** Readium Locator JSON (cross-platform position payload). */
    val locatorJson: String,
    val progressPercent: Double? = null,
    val updatedAt: String,
    val dirty: Boolean = false,
)

@Entity(tableName = "shelves")
data class ShelfEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortPosition: Long = 0,
    val updatedAt: String,
    val deletedAt: String? = null,
    val dirty: Boolean = false,
)

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val updatedAt: String,
    val deletedAt: String? = null,
    val dirty: Boolean = false,
)

@Entity(tableName = "series")
data class SeriesEntity(
    @PrimaryKey val id: String,
    val name: String,
    val updatedAt: String,
    val deletedAt: String? = null,
    val dirty: Boolean = false,
)

/** Per-entity pull cursors (updated_at watermarks, TRD §3.2 pull step 1). */
@Entity(tableName = "sync_cursors")
data class SyncCursorEntity(
    @PrimaryKey val entity: String,
    val watermark: String,
)

/** Upload queue entries surviving app death (PRD LIB-1). */
@Entity(tableName = "upload_queue")
data class UploadQueueItem(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val pendingUri: String,          // content:// uri captured at import time
    val filename: String,
    val sizeBytes: Long,
    val sha256: String,
    val contentType: String,
    val bookId: String? = null,      // set after initiate-upload
    val attempts: Int = 0,
    val state: Int = UploadState.PENDING,
)

object UploadState {
    const val PENDING = 0
    const val UPLOADED = 1       // bytes stored; complete-upload next
    const val DONE = 2
    const val FAILED = 3
}

class Converters {
    @TypeConverter
    fun fromStrings(value: List<String>): String =
        value.joinToString("\u001F")

    @TypeConverter
    fun toStrings(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split("\u001F")
}
