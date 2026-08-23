package com.bookcon.app.core

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.ContentScale
import coil.ImageLoader
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coil [ImageLoader] whose network layer carries the session's `Authorization: Bearer`
 * header, so authenticated cover-image URLs (`cover_url`) render in the library and
 * details screens (TRD §5: covers are served behind auth).
 *
 * Usage from Compose:
 * ```
 * val painter = rememberBookPainter(resolveCoverUrl(serverUrl, book.coverUrl))
 * Image(painter = painter, contentDescription = book.title, modifier = Modifier.fillMaxSize())
 * ```
 * Or with plain Coil: inject [CoverImageLoader] / the bound `coil.ImageLoader` into a
 * ViewModel and pass it to `AsyncImage(model, imageLoader, …)`.
 */
@Singleton
class CoverImageLoader @Inject constructor(
    @ApplicationContext context: Context,
    private val sessions: SessionStore,
) : ImageLoader by coil.ImageLoader.Builder(context)
    .okHttpClient(
        okhttp3.OkHttpClient.Builder()
            .addInterceptor(BearerImageInterceptor(sessions))
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build(),
    )
    .crossfade(true)
    .build()

/** Attaches the bearer token to every cover request when a session exists. */
private class BearerImageInterceptor(private val sessions: SessionStore) : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val token = sessions.current()?.accessToken
        val request = if (token.isNullOrBlank()) {
            chain.request()
        } else {
            chain.request().newBuilder().header("Authorization", "Bearer $token").build()
        }
        return chain.proceed(request)
    }
}

/** Binds the implementation as the app-wide `coil.ImageLoader` without touching di/Modules.kt. */
@Module
@InstallIn(SingletonComponent::class)
object CoverImageModule {

    @Provides
    @Singleton
    fun provideCoilImageLoader(impl: CoverImageLoader): ImageLoader = impl
}

/** Non-injectable access to the singleton loader for composable call-sites. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CoverImageEntryPoint {
    fun coverImageLoader(): ImageLoader
}

fun coverImageLoader(context: Context): ImageLoader =
    EntryPointAccessors.fromApplication(context.applicationContext, CoverImageEntryPoint::class.java)
        .coverImageLoader()

/**
 * Convenience painter for book covers backed by the authenticated [ImageLoader].
 * Pass an absolute URL (see [resolveCoverUrl]) or any Coil model.
 */
@Composable
fun rememberBookPainter(
    model: Any?,
    contentScale: ContentScale = ContentScale.Crop,
): AsyncImagePainter {
    val context = androidx.compose.ui.platform.LocalContext.current
    val imageLoader = remember(context) { coverImageLoader(context) }
    return rememberAsyncImagePainter(
        model = model,
        imageLoader = imageLoader,
        contentScale = contentScale,
    )
}

/**
 * Resolves relative `cover_url` values ("/files/covers/x.jpg") against the session server;
 * absolute http(s)/content/file/data URLs pass through untouched. Null-safe.
 */
fun resolveCoverUrl(serverUrl: String?, coverUrl: String?): String? {
    val raw = coverUrl?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val lower = raw.lowercase()
    if (lower.startsWith("http://") || lower.startsWith("https://") ||
        lower.startsWith("content://") || lower.startsWith("file://") || lower.startsWith("data:")
    ) {
        return raw
    }
    val base = serverUrl?.trimEnd('/').orEmpty()
    return if (base.isEmpty()) raw else if (raw.startsWith("/")) base + raw else "$base/$raw"
}
