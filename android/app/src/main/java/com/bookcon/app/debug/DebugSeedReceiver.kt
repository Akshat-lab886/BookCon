package com.bookcon.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bookcon.app.core.AiKeyStore
import com.bookcon.app.core.SettingsRepository
import kotlinx.coroutines.runBlocking

/**
 * DEBUG-ONLY. Seeds AI settings from intent extras so automated tests never
 * fight the soft keyboard. Registered only in the debug manifest.
 *   am broadcast -n com.bookcon.app/.debug.DebugSeedReceiver \
 *     --es provider custom --es base_url http://127.0.0.1:18099/v1 \
 *     --es model mock-model --es api_key mock-key
 */
class DebugSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val repo = SettingsRepository(context)
        val provider = intent.getStringExtra("provider") ?: return
        runBlocking {
            repo.setAiProvider(provider)
            intent.getStringExtra("base_url")?.let { repo.setAiBaseUrl(it) }
            intent.getStringExtra("model")?.let { repo.setAiModel(it) }
        }
        intent.getStringExtra("api_key")?.let { AiKeyStore(context).set(it) }
    }
}
