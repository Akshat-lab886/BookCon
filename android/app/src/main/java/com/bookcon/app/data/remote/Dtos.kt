package com.bookcon.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs mirroring api-spec/openapi.yaml v1 (TRD §3). Kept in one file for
 * contract review; the server is the source of truth.
 */

@Serializable
data class ErrorEnvelope(
    val error: ErrorBody? = null,
) {
    @Serializable
    data class ErrorBody(val code: String, val message: String, val details: List<String> = emptyList())
}

@Serializable
data class TokensDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    @SerialName("expires_in") val expiresIn: Int,
    val user: UserDto,
    val device: DeviceDto,
)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class DeviceDto(
    val id: String,
    val name: String,
    val platform: String = "android",
    @SerialName("app_version") val appVersion: String = "",
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("revoked_at") val revokedAt: String? = null,
)

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("device_name") val deviceName: String,
    @SerialName("app_version") val appVersion: String = "unknown",
    val platform: String = "android",
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("app_version") val appVersion: String = "unknown",
    val platform: String = "android",
)

@Serializable
data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class LogoutRequest(@SerialName("refresh_token") val refreshToken: String? = null)

// --- Books ---------------------------------------------------------------------

@Serializable
data class BookDto(
    val id: String,
    val format: String,
    val status: String = "ready",
    @SerialName("status_message") val statusMessage: String? = null,
    val title: String,
    val authors: List<String> = emptyList(),
    val description: String = "",
    val language: String? = null,
    val publisher: String? = null,
    @SerialName("published_date") val publishedDate: String? = null,
    @SerialName("series_id") val seriesId: String? = null,
    @SerialName("series_index") val seriesIndex: Double? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("file_size_bytes") val fileSizeBytes: Long? = null,
    @SerialName("page_count") val pageCount: Int? = null,
    @SerialName("word_count") val wordCount: Int? = null,
    @SerialName("tag_ids") val tagIds: List<String> = emptyList(),
    @SerialName("shelf_ids") val shelfIds: List<String> = emptyList(),
    @SerialName("added_at") val addedAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class BookPageDto(
    val items: List<BookDto>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
data class InitiateUploadRequest(
    val filename: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val sha256: String,
    @SerialName("content_type") val contentType: String,
)

@Serializable
data class InitiateUploadResponse(
    val outcome: String, // upload_required | duplicate
    @SerialName("book_id") val bookId: String,
    @SerialName("upload_url") val uploadUrl: String? = null,
    val method: String? = null,
    val headers: Map<String, String>? = null,
    @SerialName("expires_in") val expiresIn: Int? = null,
)

@Serializable
data class FileUrlResponse(val url: String, @SerialName("expires_in") val expiresIn: Int)

// --- Organize -------------------------------------------------------------------

@Serializable
data class ShelfDto(
    val id: String,
    val name: String,
    @SerialName("sort_position") val sortPosition: Long = 0,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class TagDto(
    val id: String,
    val name: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class SeriesDto(
    val id: String,
    val name: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class NameRequest(val name: String)

// --- Reading ----------------------------------------------------------------------

@Serializable
data class LocatorDto(
    val href: String,
    val type: String = "application/xhtml+xml",
    val title: String? = null,
    val locations: Map<String, kotlinx.serialization.json.JsonElement>? = null,
    val text: Map<String, kotlinx.serialization.json.JsonElement>? = null,
)

@Serializable
data class AnnotationDto(
    val id: String,
    @SerialName("book_id") val bookId: String,
    val type: String, // highlight | underline | area
    val locator: LocatorDto,
    val color: String = "yellow",
    val note: String = "",
    @SerialName("annotation_tags") val annotationTags: List<String> = emptyList(),
    val excerpt: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class BookmarkDto(
    val id: String,
    @SerialName("book_id") val bookId: String,
    val locator: LocatorDto,
    val label: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class PositionDto(
    @SerialName("book_id") val bookId: String,
    val locator: LocatorDto,
    @SerialName("progress_percent") val progressPercent: Double? = null,
    @SerialName("updated_at") val updatedAt: String,
)

// --- Sync --------------------------------------------------------------------------

@Serializable
data class SyncPullRequest(
    /** entity -> ISO-8601 watermark. Missing entity = full sync for that entity. */
    val cursors: Map<String, String> = emptyMap(),
    val limit: Int = 500,
)

@Serializable
data class SyncPullResponse(
    val cursors: Map<String, String> = emptyMap(),
    @SerialName("has_more") val hasMore: Boolean = false,
    val books: List<BookDto> = emptyList(),
    val annotations: List<AnnotationDto> = emptyList(),
    val bookmarks: List<BookmarkDto> = emptyList(),
    val positions: List<PositionDto> = emptyList(),
    val shelves: List<ShelfDto> = emptyList(),
    val tags: List<TagDto> = emptyList(),
    val series: List<SeriesDto> = emptyList(),
)

@Serializable
data class SyncPushRequest(
    val annotations: List<AnnotationDto> = emptyList(),
    val bookmarks: List<BookmarkDto> = emptyList(),
    val positions: List<PositionDto> = emptyList(),
    val shelves: List<ShelfDto> = emptyList(),
    val tags: List<TagDto> = emptyList(),
    val series: List<SeriesDto> = emptyList(),
)

@Serializable
data class SyncPushResponse(
    val accepted: Map<String, List<String>> = emptyMap(),
    val rejected: Map<String, List<String>> = emptyMap(),
    val authoritative: Map<String, List<kotlinx.serialization.json.JsonObject>> = emptyMap(),
)

@Serializable
data class StorageStatsDto(
    @SerialName("total_bytes") val totalBytes: Long,
    @SerialName("book_count") val bookCount: Int,
    @SerialName("annotation_count") val annotationCount: Int,
    @SerialName("bookmark_count") val bookmarkCount: Int = 0,
)
