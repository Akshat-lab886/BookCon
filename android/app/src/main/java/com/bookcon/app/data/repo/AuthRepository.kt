package com.bookcon.app.data.repo

import com.bookcon.app.core.Session
import com.bookcon.app.core.SessionStore
import com.bookcon.app.data.remote.ApiProvider
import com.bookcon.app.data.remote.LoginRequest
import com.bookcon.app.data.remote.LogoutRequest
import com.bookcon.app.data.remote.RefreshRequest
import com.bookcon.app.data.remote.RegisterRequest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AuthResult {
    data class Success(val session: Session) : AuthResult
    data class Failure(val message: String) : AuthResult
}

@Singleton
class AuthRepository @Inject constructor(
    private val api: ApiProvider,
    private val sessions: SessionStore,
    private val refresher: com.bookcon.app.data.remote.TokenRefresher,
) {

    /** PRD AUTH-1/2: email+password sign-up / login with device registration. */
    suspend fun emailAuth(
        serverUrl: String,
        email: String,
        password: String,
        displayName: String,
        deviceName: String,
        registerNew: Boolean,
    ): AuthResult = try {
        android.util.Log.w("BookConAuth", "emailAuth start register=$registerNew")
        sessions.update(Session(serverUrl = serverUrl, accessToken = "", refreshToken = "", userId = null, deviceId = null, email = email))
        android.util.Log.w("BookConAuth", "placeholder stored")
        val resp = if (registerNew) {
            api.get().register(RegisterRequest(email = email, password = password, displayName = displayName, deviceName = deviceName, appVersion = appVersion()))
        } else {
            api.get().login(LoginRequest(email = email, password = password, deviceName = deviceName, appVersion = appVersion()))
        }
        if (resp.isSuccessful && resp.body() != null) {
            val tokens = requireNotNull(resp.body())
            val session = Session(
                serverUrl = serverUrl.trimEnd('/'),
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                userId = tokens.user.id,
                deviceId = tokens.device.id,
                email = tokens.user.email,
            )
            android.util.Log.w("BookConAuth", "got tokens rf=${tokens.refreshToken.length} ac=${tokens.accessToken.length}")
            sessions.update(session)
            android.util.Log.i("BookConAuth", "login ok; session stored curRf=${sessions.current()?.refreshToken?.length}")
            AuthResult.Success(session)
        } else {
            sessions.update(null)
            AuthResult.Failure(errorMessage(resp.code(), resp.errorBody()?.string()))
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        // Session publication switches the UI away from AuthScreen, which cancels this
        // coroutine as part of normal teardown — propagate, never convert to a failure.
        throw e
    } catch (e: Exception) {
        android.util.Log.e("BookConAuth", "emailAuth failed", e)
        sessions.update(null)
        AuthResult.Failure(e.message ?: "Connection failed.")
    }

    /** PRD AUTH-2: transparent refresh; used by force-sync (single-flight via [TokenRefresher]). */
    suspend fun refreshNow(): Boolean =
        refresher.refreshStale(sessions.current()) != null

    /** PRD AUTH-4: removing a device invalidates its refresh token server-side. */
    suspend fun removeDevice(deviceId: String): Boolean =
        runCatching { api.get().deleteDevice(deviceId).isSuccessful }.getOrDefault(false)

    suspend fun listDevices(): List<com.bookcon.app.data.remote.DeviceDto> =
        runCatching { api.get().devices().body().orEmpty() }.getOrDefault(emptyList())

    suspend fun logout() {
        val session = sessions.current() ?: return
        runCatching { api.get().logout(LogoutRequest(session.refreshToken)) }
        sessions.update(null)
    }

    private fun errorMessage(code: Int, body: String?): String = when (code) {
        401 -> "Incorrect email or password."
        409 -> "An account with this email already exists."
        429 -> "Too many attempts. Try again in a minute."
        else -> parseMessage(body) ?: "Sign-in failed (HTTP $code)."
    }

    private fun parseMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            json.decodeFromString<kotlinx.serialization.json.JsonObject>(body)["error"]
            ?.let { err ->
                (err as? kotlinx.serialization.json.JsonObject)?.get("message")
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun appVersion(): String = try {
        "1.0.0" // keep literal to avoid BuildManager dependency in repo layer
    } catch (_: Exception) {
        "unknown"
    }

    companion object {
        fun suggestedDeviceName(): String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".replaceFirstChar { it.uppercase() }
        fun newId(): String = UUID.randomUUID().toString()
    }
}
