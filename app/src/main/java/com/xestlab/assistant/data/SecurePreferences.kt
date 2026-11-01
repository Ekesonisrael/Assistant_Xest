package com.xestlab.assistant.data

import android.content.Context
import android.content.SharedPreferences

/**
 * App-private credential storage for optional cloud services.
 * Keys stored here are strictly excluded from JSON exports and database backups.
 */
class SecurePreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("xestlab_secure_prefs", Context.MODE_PRIVATE)

    var isAiEnabled: Boolean
        get() = prefs.getBoolean("ai_enabled", false)
        set(value) = prefs.edit().putBoolean("ai_enabled", value).apply()

    var geminiApiKey: String
        get() = prefs.getString("gemini_api_key", "") ?: ""
        set(value) = prefs.edit().putString("gemini_api_key", value.trim()).apply()

    var geminiModel: String
        get() = prefs.getString("gemini_model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        set(value) = prefs.edit().putString("gemini_model", value.trim().ifBlank { "gemini-2.5-flash" }).apply()

    fun hasGeminiKey(): Boolean = geminiApiKey.isNotBlank()

    var isFishAudioEnabled: Boolean
        get() = prefs.getBoolean("fish_audio_enabled", false)
        set(value) = prefs.edit().putBoolean("fish_audio_enabled", value).apply()

    var fishAudioApiKey: String
        get() = prefs.getString("fish_audio_api_key", "") ?: ""
        set(value) = prefs.edit().putString("fish_audio_api_key", value.trim()).apply()

    var fishAudioVoiceId: String
        get() = prefs.getString("fish_audio_voice_id", "") ?: ""
        set(value) = prefs.edit().putString("fish_audio_voice_id", value.trim()).apply()

    var isImapEnabled: Boolean
        get() = prefs.getBoolean("imap_enabled", false)
        set(value) = prefs.edit().putBoolean("imap_enabled", value).apply()

    var imapHost: String
        get() = prefs.getString("imap_host", "imap.gmail.com") ?: "imap.gmail.com"
        set(value) = prefs.edit().putString("imap_host", value.trim()).apply()

    var imapPort: Int
        get() = prefs.getInt("imap_port", 993)
        set(value) = prefs.edit().putInt("imap_port", value).apply()

    var imapUser: String
        get() = prefs.getString("imap_user", "") ?: ""
        set(value) = prefs.edit().putString("imap_user", value.trim()).apply()

    var imapPassword: String
        get() = prefs.getString("imap_password", "") ?: ""
        set(value) = prefs.edit().putString("imap_password", value).apply()

    var isImapWifiOnly: Boolean
        get() = prefs.getBoolean("imap_wifi_only", true)
        set(value) = prefs.edit().putBoolean("imap_wifi_only", value).apply()

    var lastEmailCheckTimestamp: Long
        get() = prefs.getLong("last_email_check_ts", 0L)
        set(value) = prefs.edit().putLong("last_email_check_ts", value).apply()
}
