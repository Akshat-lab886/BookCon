package com.bookcon.app.data.remote

import com.bookcon.app.core.Session
import com.bookcon.app.core.SessionStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Single-flight access-token refresh.
 *
 * Every refresh path in the app MUST go through [refreshStale]: the server enforces
 * single-use refresh tokens with reuse detection (presenting an already-rotated token
 * revokes the whole device family), so two concurrent refreshers would instantly sign
 * the user out. The mutex plus the re-read-under-lock guard makes concurrent callers
 * coalesce: whoever arrives second observes a superseded refresh token and simply
 * adopts the newer session instead of hitting the network.
 */
@Singleton
class TokenRefresher @Inject constructor(
    private val sessions: SessionStore,
    // Provider<> breaks the Dagger cycle: ApiProvider -> TokenRefresher -> ApiProvider.
    private val apiProvider: Provider<ApiProvider>,
) {
    private val mutex = Mutex()

    /**
     * Refreshes the stored session unless another caller already rotated past
     * [stale]. Returns the current (possibly newly rotated) session, or null when
     * the refresh failed and the user must re-authenticate.
     */
    suspend fun refreshStale(stale: Session?): Session? {
        if (stale == null || stale.refreshToken.isBlank()) return null
        return mutex.withLock {
            // Another caller may have rotated while this one waited on the lock.
            val latest = sessions.current()
            when {
                latest == null -> null
                latest.refreshToken != stale.refreshToken -> latest
                else -> refreshOnce(latest)
            }
        }
    }

    private suspend fun refreshOnce(stale: Session): Session? = try {
        val resp = apiProvider.get().get().refresh(RefreshRequest(stale.refreshToken))
        android.util.Log.w("BookConAuth", "refreshOnce http=${resp.code()}")
        val tokens = resp.body()
        if (resp.isSuccessful && tokens != null) {
            val rotated = stale.copy(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
            )
            sessions.update(rotated)
            sessions.current()
        } else {
            // Reuse detected or expired refresh token → signed out (server already
            // revoked the family; keeping local state would cause endless 401 churn).
            sessions.update(null)
            null
        }
    } catch (_: Exception) {
        null
    }
}
