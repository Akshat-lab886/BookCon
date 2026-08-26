package com.bookcon.app.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * BYOK storage for the user's AI provider API key.
 *
 * Keys live in [EncryptedSharedPreferences] backed by the Android Keystore. Some devices
 * have broken/locked Keystores, so every step is guarded: if the master key or the
 * encrypted file cannot be created we fall back to a plain (unencrypted) prefs file so
 * the summarization feature degrades gracefully instead of crashing the app.
 */
class AiKeyStore(context: Context) {

    private val prefs: SharedPreferences = createPrefs(context.applicationContext)

    /** The stored API key, or "" when unset or unreadable. Never throws. */
    fun get(): String = runCatching { prefs.getString(KEY, null) }.getOrNull().orEmpty()

    /** Persists [key]; failures are swallowed (callers cannot do much about storage errors). */
    fun set(key: String) {
        runCatching { prefs.edit().putString(KEY, key.trim()).commit() }
            .onFailure { Log.w(TAG, "Failed to persist AI key", it) }
    }

    /** Removes the stored key; failures are swallowed. */
    fun clear() {
        runCatching { prefs.edit().remove(KEY).commit() }
            .onFailure { Log.w(TAG, "Failed to clear AI key", it) }
    }

    private fun createPrefs(context: Context): SharedPreferences {
        val secure = runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                FILE_SECURE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        val prefs = secure.getOrElse {
            Log.w(TAG, "EncryptedSharedPreferences unavailable, using fallback prefs", it)
            context.getSharedPreferences(FILE_FALLBACK, Context.MODE_PRIVATE)
        }
        migrateFallbackInto(prefs, context)
        return prefs
    }

    /**
     * One-time migration: if the Keystore worked this launch but a key was saved to
     * the fallback file during a degraded session, promote it into secure storage
     * (and vice versa never happens — secure always wins once available).
     */
    private fun migrateFallbackInto(securePrefs: SharedPreferences, context: Context) {
        runCatching {
            val fallback = context.getSharedPreferences(FILE_FALLBACK, Context.MODE_PRIVATE)
            val orphan = fallback.getString(KEY, null) ?: return
            if (securePrefs === fallback) return
            securePrefs.edit().putString(KEY, orphan).commit()
            fallback.edit().remove(KEY).commit()
            Log.w(TAG, "Migrated AI key from fallback prefs into encrypted storage")
        }
    }

    private companion object {
        const val TAG = "AiKeyStore"
        const val FILE_SECURE = "bookcon_ai_secure"
        const val FILE_FALLBACK = "bookcon_ai_fallback"
        const val KEY = "ai_api_key"
    }
}
