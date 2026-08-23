package com.bookcon.app.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Server URL + session tokens. Tokens live in EncryptedSharedPreferences backed by the
 * Android Keystore (TRD §5: client secrets at rest).
 */
@Singleton
class SessionStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "bookcon_secure_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _session = MutableStateFlow(loadSession())
    val session: StateFlow<Session?> = _session

    fun current(): Session? = _session.value

    fun update(session: Session?) {
        // Single transaction: a process death between two edits() could persist
        // a half-written session, which loadSession() must never crash on.
        prefs.edit().apply {
            if (session == null) {
                clear()
            } else {
                putString(KEY_ACCESS, session.accessToken)
                putString(KEY_REFRESH, session.refreshToken)
                putString(KEY_SERVER, session.serverUrl)
                putString(KEY_USER_ID, session.userId)
                putString(KEY_DEVICE_ID, session.deviceId)
                putString(KEY_EMAIL, session.email)
            }
        }.apply()
        _session.value = session
    }

    fun rotateTokens(accessToken: String, refreshToken: String) {
        val s = _session.value ?: return
        update(s.copy(accessToken = accessToken, refreshToken = refreshToken))
    }

    /** Tolerant load: any missing required key → treat as signed out (no crash loop). */
    private fun loadSession(): Session? {
        val serverUrl = prefs.getString(KEY_SERVER, null)
        val access = prefs.getString(KEY_ACCESS, null)
        val refresh = prefs.getString(KEY_REFRESH, null)
        if (serverUrl.isNullOrBlank() || access.isNullOrBlank() || refresh.isNullOrBlank()) {
            if (access != null || refresh != null || serverUrl != null) {
                prefs.edit().clear().apply() // partial write → wipe and force re-login
            }
            return null
        }
        return Session(
            serverUrl = serverUrl,
            accessToken = access,
            refreshToken = refresh,
            userId = prefs.getString(KEY_USER_ID, null),
            deviceId = prefs.getString(KEY_DEVICE_ID, null),
            email = prefs.getString(KEY_EMAIL, "").orEmpty(),
        )
    }

    companion object {
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_SERVER = "server_url"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_EMAIL = "email"
    }
}

data class Session(
    val serverUrl: String,
    val accessToken: String,
    val refreshToken: String,
    val userId: String?,
    val deviceId: String?,
    val email: String,
)
