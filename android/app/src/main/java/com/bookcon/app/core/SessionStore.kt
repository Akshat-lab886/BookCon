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

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences = createPrefs(appContext)

    private val _session = MutableStateFlow(loadSession())
    val session: StateFlow<Session?> = _session

    fun current(): Session? = _session.value

    /**
     * Same call [com.bookcon.app.core.AiKeyStore] already guards: some devices have a
     * locked or corrupt Keystore and `EncryptedSharedPreferences.create` throws for
     * them. This class is a @Singleton injected into MainActivity, ApiProvider,
     * TokenRefresher, AuthRepository, both sync workers and several ViewModels, so an
     * unguarded throw here escaped the Hilt graph and crashed the app on the first
     * frame — recoverable only by clearing app data. Degrading to plain prefs means
     * the user can at least sign in and use the app.
     */
    private fun createPrefs(context: Context): SharedPreferences {
        val secure = runCatching {
            EncryptedSharedPreferences.create(
                context,
                FILE_SECURE,
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        return secure.getOrElse { cause ->
            android.util.Log.w(TAG, "EncryptedSharedPreferences unavailable; using fallback", cause)
            context.getSharedPreferences(FILE_FALLBACK, Context.MODE_PRIVATE)
        }
    }

    // commit(), not apply(): a sign-out has to be on disk before this returns. With
    // apply() the write is still in flight when the process is killed — which is
    // exactly what happens right after signing out — and the user comes back to a
    // session they believe they ended. Lint's ApplySharedPref advice is wrong here.
    @Suppress("ApplySharedPref")
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
        }.commit()
        // createPrefs() picks ONE of the two files for the life of the process, so
        // `prefs` is not necessarily the file that was actually written last time.
        // If an earlier run found the Keystore unavailable it stored a live
        // refresh token in the PLAINTEXT fallback; a later run, with the Keystore
        // working, writes to the encrypted file and never looks at the fallback
        // again. Signing out then cleared only the encrypted copy and left a
        // working refresh token sitting in the clear on disk — a session the user
        // believed they had ended. Both files are cleared on sign-out.
        if (session == null) {
            runCatching {
                appContext.getSharedPreferences(FILE_FALLBACK, Context.MODE_PRIVATE)
                    .edit().clear().commit()
            }
        }
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
                // commit() for the same reason as update(): a partial session must not
                // survive a process death and get read back as a usable one.
                prefs.edit().clear().commit() // partial write → wipe and force re-login
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
        private const val TAG = "SessionStore"
        private const val FILE_SECURE = "bookcon_secure_prefs"
        private const val FILE_FALLBACK = "bookcon_session_fallback"
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
