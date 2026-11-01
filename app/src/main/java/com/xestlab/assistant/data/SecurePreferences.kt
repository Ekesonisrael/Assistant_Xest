@file:Suppress("DEPRECATION")

package com.xestlab.assistant.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class LlmProvider {
    GEMINI,
    GROQ;

    companion object {
        fun fromString(raw: String?): LlmProvider {
            return if (raw.equals("GROQ", ignoreCase = true)) GROQ else GEMINI
        }
    }
}

/**
 * Hardware-backed AES-256 EncryptedSharedPreferences storage for all sensitive credentials:
 * - Gemini API Key
 * - Groq API Key
 * - ElevenLabs API Key
 * - IMAP Password
 *
 * Credentials are never stored in plain text and are strictly excluded from JSON exports.
 */
class SecurePreferences(context: Context) {

    companion object {
        private const val VAULT_FILE_NAME = "xestlab_encrypted_credentials_v2"
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = openEncryptedVault()

    private fun openEncryptedVault(): SharedPreferences {
        return try {
            createEncryptedPrefs()
        } catch (_: Exception) {
            // If Android Keystore invalidated an older keyset on reinstall, reset vault cleanly
            // and re-initialize AES256 EncryptedSharedPreferences (never falling back to plaintext).
            appContext.deleteSharedPreferences(VAULT_FILE_NAME)
            createEncryptedPrefs()
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            appContext,
            VAULT_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var isAiEnabled: Boolean
        get() = prefs.getBoolean("ai_enabled", false)
        set(value) = prefs.edit().putBoolean("ai_enabled", value).apply()

    var llmProvider: LlmProvider
        get() = LlmProvider.fromString(prefs.getString("llm_provider", LlmProvider.GEMINI.name))
        set(value) = prefs.edit().putString("llm_provider", value.name).apply()

    var geminiApiKey: String
        get() = prefs.getString("gemini_api_key", "") ?: ""
        set(value) = prefs.edit().putString("gemini_api_key", value.trim()).apply()

    var geminiModel: String
        get() = prefs.getString("gemini_model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        set(value) = prefs.edit()
            .putString("gemini_model", value.trim().ifBlank { "gemini-2.5-flash" })
            .apply()

    var groqApiKey: String
        get() = prefs.getString("groq_api_key", "") ?: ""
        set(value) = prefs.edit().putString("groq_api_key", value.trim()).apply()

    var groqModel: String
        get() = prefs.getString("groq_model", "llama3-70b-8192") ?: "llama3-70b-8192"
        set(value) = prefs.edit()
            .putString("groq_model", value.trim().ifBlank { "llama3-70b-8192" })
            .apply()

    fun hasGeminiKey(): Boolean = geminiApiKey.isNotBlank()

    fun hasGroqKey(): Boolean = groqApiKey.isNotBlank()

    fun hasActiveLlmKey(): Boolean {
        return when (llmProvider) {
            LlmProvider.GEMINI -> hasGeminiKey()
            LlmProvider.GROQ -> hasGroqKey()
        }
    }

    var elevenLabsApiKey: String
        get() = prefs.getString("elevenlabs_api_key", "") ?: ""
        set(value) = prefs.edit().putString("elevenlabs_api_key", value.trim()).apply()

    fun hasElevenLabsKey(): Boolean = elevenLabsApiKey.isNotBlank()

    var isImapEnabled: Boolean
        get() = prefs.getBoolean("imap_enabled", false)
        set(value) = prefs.edit().putBoolean("imap_enabled", value).apply()

    var imapHost: String
        get() = prefs.getString("imap_host", "imap.gmail.com") ?: "imap.gmail.com"
        set(value) = prefs.edit().putString("imap_host", value.trim().ifBlank { "imap.gmail.com" }).apply()

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
        get() = prefs.getBoolean("imap_wifi_only", false)
        set(value) = prefs.edit().putBoolean("imap_wifi_only", value).apply()

    var lastEmailCheckTimestamp: Long
        get() = prefs.getLong("last_email_check_ts", 0L)
        set(value) = prefs.edit().putLong("last_email_check_ts", value).apply()
}
