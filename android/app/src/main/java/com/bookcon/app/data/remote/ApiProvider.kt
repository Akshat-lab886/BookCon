package com.bookcon.app.data.remote

import com.bookcon.app.core.SessionStore
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds/holds the ApiService. The Retrofit instance is rebuilt when the user changes
 * the server URL (first-run setup / Settings). All callers fetch via [get].
 */
@Singleton
class ApiProvider @Inject constructor(
    private val sessions: SessionStore,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        coerceInputValues = true
        explicitNulls = false
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(sessions))
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .authenticator(TokenAuthenticator(sessions) { get() })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var cachedUrl: String? = null
    @Volatile private var cachedApi: ApiService? = null

    @Synchronized
    fun get(): ApiService {
        val url = (sessions.current()?.serverUrl ?: DEFAULT_SERVER).trimEnd('/')
        val effective = "$url/api/v1/"
        cachedApi?.takeIf { cachedUrl == effective }?.let { return it }
        val api = Retrofit.Builder()
            .baseUrl(effective)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json; charset=utf-8".toMediaType()))
            .build()
            .create(ApiService::class.java)
        cachedApi = api
        cachedUrl = effective
        return api
    }

    companion object {
        const val DEFAULT_SERVER = "http://10.0.2.2:8000"
    }
}
