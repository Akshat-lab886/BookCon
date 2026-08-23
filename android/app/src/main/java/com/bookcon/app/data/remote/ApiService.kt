package com.bookcon.app.data.remote

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** BookCon API v1 — mirrors api-spec/openapi.yaml (TRD §3.1). */
interface ApiService {

    // Auth ------------------------------------------------------------------
    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): Response<TokensDto>

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): Response<TokensDto>

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): Response<TokensDto>

    @POST("auth/logout")
    suspend fun logout(@Body body: LogoutRequest): Response<Void>

    @GET("me")
    suspend fun me(): Response<UserDto>

    // Devices -----------------------------------------------------------------
    @GET("devices")
    suspend fun devices(): Response<List<DeviceDto>>

    @DELETE("devices/{deviceId}")
    suspend fun deleteDevice(@Path("deviceId") deviceId: String): Response<Void>

    // Books --------------------------------------------------------------------
    @GET("books")
    suspend fun books(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 50,
        @Query("q") q: String? = null,
        @Query("shelf_id") shelfId: String? = null,
        @Query("tag_id") tagId: String? = null,
        @Query("series_id") seriesId: String? = null,
        @Query("format") format: String? = null,
        @Query("sort") sort: String = "recent",
        @Query("include_deleted") includeDeleted: Boolean = false,
    ): Response<BookPageDto>

    @POST("books/initiate-upload")
    suspend fun initiateUpload(@Body body: InitiateUploadRequest): Response<InitiateUploadResponse>

    @POST("books/{bookId}/complete-upload")
    suspend fun completeUpload(@Path("bookId") bookId: String): Response<BookDto>

    @GET("books/{bookId}")
    suspend fun book(@Path("bookId") bookId: String): Response<BookDto>

    @PATCH("books/{bookId}")
    suspend fun patchBook(
        @Path("bookId") bookId: String,
        @Body patch: Map<String, kotlinx.serialization.json.JsonElement>,
    ): Response<BookDto>

    @DELETE("books/{bookId}")
    suspend fun deleteBook(@Path("bookId") bookId: String): Response<Void>

    @GET("books/{bookId}/file-url")
    suspend fun fileUrl(@Path("bookId") bookId: String, @Query("download") download: Boolean = true): Response<FileUrlResponse>

    // Organize ---------------------------------------------------------------------
    @GET("shelves")
    suspend fun shelves(): Response<List<ShelfDto>>

    @POST("shelves")
    suspend fun createShelf(@Body body: NameRequest): Response<ShelfDto>

    @DELETE("shelves/{shelfId}")
    suspend fun deleteShelf(@Path("shelfId") shelfId: String): Response<Void>

    @GET("tags")
    suspend fun tags(): Response<List<TagDto>>

    @POST("tags")
    suspend fun createTag(@Body body: NameRequest): Response<TagDto>

    @DELETE("tags/{tagId}")
    suspend fun deleteTag(@Path("tagId") tagId: String): Response<Void>

    @GET("series")
    suspend fun series(): Response<List<SeriesDto>>

    @POST("series")
    suspend fun createSeries(@Body body: NameRequest): Response<SeriesDto>

    // Reading --------------------------------------------------------------------------
    @GET("annotations")
    suspend fun annotations(
        @Query("book_id") bookId: String? = null,
        @Query("since") since: String? = null,
    ): Response<List<AnnotationDto>>

    @POST("annotations")
    suspend fun createAnnotation(@Body annotation: AnnotationDto): Response<AnnotationDto>

    @PATCH("annotations/{annotationId}")
    suspend fun patchAnnotation(
        @Path("annotationId") id: String,
        @Body patch: Map<String, kotlinx.serialization.json.JsonElement>,
    ): Response<AnnotationDto>

    @DELETE("annotations/{annotationId}")
    suspend fun deleteAnnotation(@Path("annotationId") id: String): Response<Void>

    @GET("bookmarks")
    suspend fun bookmarks(@Query("book_id") bookId: String? = null): Response<List<BookmarkDto>>

    @POST("bookmarks")
    suspend fun createBookmark(@Body bookmark: BookmarkDto): Response<BookmarkDto>

    @DELETE("bookmarks/{bookmarkId}")
    suspend fun deleteBookmark(@Path("bookmarkId") id: String): Response<Void>

    @PUT("positions/{bookId}")
    suspend fun putPosition(@Path("bookId") bookId: String, @Body position: PositionDto): Response<PositionDto>

    // Positions for other devices arrive via /sync/pull; there is no bulk GET endpoint.

    // Sync -------------------------------------------------------------------------------
    @POST("sync/pull")
    suspend fun syncPull(@Body body: SyncPullRequest): Response<SyncPullResponse>

    @POST("sync/push")
    suspend fun syncPush(@Body body: SyncPushRequest): Response<SyncPushResponse>

    // Storage / misc -----------------------------------------------------------------------
    @GET("storage/stats")
    suspend fun storageStats(): Response<StorageStatsDto>

    @GET("healthz")
    suspend fun healthz(): Response<ResponseBody>
}
