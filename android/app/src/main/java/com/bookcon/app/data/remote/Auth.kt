package com.bookcon.app.data.remote

import com.bookcon.app.core.Session
import com.bookcon.app.core.SessionStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/** Attaches the bearer access token to API calls. */
class AuthInterceptor(private val sessions: SessionStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val session = sessions.current() ?: return chain.proceed(chain.request())
        val request = chain.request().newBuilder()
            .header("Authorization", "Bearer ${session.accessToken}")
            .build()
        return chain.proceed(request)
    }
}

/**
 * 401 → rotate refresh once (mutex-guarded so parallel calls don't stampede),
 * then retry the original request. Reuse-detection on the server revokes the
 * family; we then force sign-out.
 */
class TokenAuthenticator(
    private val sessions: SessionStore,
    private val apiProvider: () -> ApiService,
) : Authenticator {

    private val mutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        // Never retry an already-retried request: if the fresh token is also
        // rejected we must stop instead of churning refresh tokens (M-11).
        if (response.priorResponse != null) return null
        if (response.request.header("Authorization") == null) return null
        val current = sessions.current() ?: return null
        if (current.accessToken != response.request.header("Authorization")?.removePrefix("Bearer ")) {
            // Another thread already rotated while this response was in flight —
            // just retry with the newer token, no refresh call needed.
            return response.request.newBuilder()
                .header("Authorization", "Bearer ${sessions.current()?.accessToken}")
                .build()
        }
        val rotated = runBlocking { mutex.withLock { refreshOnce(current) } } ?: return null
        return response.request.newBuilder()
            .header("Authorization", "Bearer ${rotated.accessToken}")
            .build()
    }

    /** Suspend body executed inside runBlocking on the OkHttp authenticator thread. */
    private suspend fun refreshOnce(stale: Session): Session? {
        return try {
            val resp = apiProvider().refresh(RefreshRequest(stale.refreshToken))
            val tokens = resp.body()
            if (resp.isSuccessful && tokens != null) {
                sessions.update(
                    stale.copy(accessToken = tokens.accessToken, refreshToken = tokens.refreshToken),
                )
                sessions.current()
            } else {
                sessions.update(null) // reuse detected or expired → signed out
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
