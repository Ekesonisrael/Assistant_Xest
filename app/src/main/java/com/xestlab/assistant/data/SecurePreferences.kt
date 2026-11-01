@file:Suppress("DEPRECATION")

package com.xestlab.assistant.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class LlmProvider {
    GEMINI,
    GROQ;

    companion object {
        fun fromString(raw: String?): LlmProvider {
            return if (raw.equals("GEMINI", ignoreCase = true)) GEMINI else GROQ
        }
    }
}

/**
 * Thread-safe singleton AES-256 EncryptedSharedPreferences vault for all sensitive credentials:
 * - Groq API Key (Primary Engine)
 * - Gemini API Key (Disabled for now)
 * - Picovoice Access Key
 * - ElevenLabs API Key
 * - IMAP Password
 *
 * Uses synchronous .commit() writes so keys are persisted immediately and never stored in plain text.
 */
@SuppressLint("ApplySharedPref")
class SecurePreferences(context: Context) {

    companion object {
        private const val VAULT_FILE_NAME = "xestlab_encrypted_credentials_v2"
        private val LOCK = Any()

        @Volatile
        private var sharedEncryptedPrefs: SharedPreferences? = null

        private fun getOrCreateEncryptedPrefs(appContext: Context): SharedPreferences {
            sharedEncryptedPrefs?.let { return it }
            synchronized(LOCK) {
                sharedEncryptedPrefs?.let { return it }
                val created = try {
                    buildEncryptedPrefs(appContext)
                } catch (_: Exception) {
                    appContext.deleteSharedPreferences(VAULT_FILE_NAME)
                    buildEncryptedPrefs(appContext)
                }
                sharedEncryptedPrefs = created
                return created
            }
        }

        private fun buildEncryptedPrefs(appContext: Context): SharedPreferences {
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

        fun sanitizeApiKey(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            return raw
                .trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
                .removeSurrounding("<", ">")
                .removePrefix("xi-api-key:")
                .removePrefix("xi-api-key")
                .removePrefix("Bearer ")
                .removePrefix("bearer ")
                .trim()
                .replace(Regex("\\s+"), "")
        }
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = getOrCreateEncryptedPrefs(appContext)

    var isAiEnabled: Boolean
        get() = prefs.getBoolean("ai_enabled_v3", true)
        set(value) {
            prefs.edit().putBoolean("ai_enabled_v3", value).commit()
        }

    var llmProvider: LlmProvider
        get() = LlmProvider.fromString(prefs.getString("llm_provider_v3", LlmProvider.GROQ.name))
        set(value) {
            prefs.edit().putString("llm_provider_v3", value.name).commit()
        }

    var geminiApiKey: String
        get() = sanitizeApiKey(prefs.getString("gemini_api_key", null))
        set(value) {
            prefs.edit().putString("gemini_api_key", sanitizeApiKey(value)).commit()
        }

    var groqApiKey: String
        get() = sanitizeApiKey(prefs.getString("groq_api_key", null))
        set(value) {
            prefs.edit().putString("groq_api_key", sanitizeApiKey(value)).commit()
        }

    fun getKeyForProvider(provider: LlmProvider): String? {
        val raw = when (provider) {
            LlmProvider.GROQ -> groqApiKey
            LlmProvider.GEMINI -> geminiApiKey
        }
        return raw.takeIf { it.isNotBlank() }
    }

    fun maskedKeyPreview(provider: LlmProvider): String {
        val key = getKeyForProvider(provider) ?: return "None saved"
        return if (key.length <= 8) {
            "Saved (${key.length} chars)"
        } else {
            "${key.take(4)}...${key.takeLast(4)} (${key.length} chars)"
        }
    }

    fun hasGeminiKey(): Boolean = geminiApiKey.isNotBlank()

    fun hasGroqKey(): Boolean = groqApiKey.isNotBlank()

    fun hasActiveLlmKey(): Boolean = groqApiKey.isNotBlank()

    var elevenLabsApiKey: String
        get() = sanitizeApiKey(prefs.getString("elevenlabs_api_key", null))
        set(value) {
            prefs.edit().putString("elevenlabs_api_key", sanitizeApiKey(value)).commit()
        }

    fun hasElevenLabsKey(): Boolean = elevenLabsApiKey.isNotBlank()

    fun maskedElevenLabsKeyPreview(): String {
        val key = elevenLabsApiKey
        if (key.isBlank()) return "None saved"
        return if (key.length <= 8) {
            "Saved (${key.length} chars)"
        } else {
            "${key.take(4)}...${key.takeLast(4)} (${key.length} chars)"
        }
    }

    var isImapEnabled: Boolean
        get() = prefs.getBoolean("imap_enabled", false)
        set(value) {
            prefs.edit().putBoolean("imap_enabled", value).commit()
        }

    var imapHost: String
        get() = prefs.getString("imap_host", "imap.gmail.com") ?: "imap.gmail.com"
        set(value) {
            prefs.edit().putString("imap_host", value.trim().ifBlank { "imap.gmail.com" }).commit()
        }

    var imapPort: Int
        get() = prefs.getInt("imap_port", 993)
        set(value) {
            prefs.edit().putInt("imap_port", value).commit()
        }

    var imapUser: String
        get() = prefs.getString("imap_user", "") ?: ""
        set(value) {
            prefs.edit().putString("imap_user", value.trim()).commit()
        }

    var imapPassword: String
        get() = prefs.getString("imap_password", "") ?: ""
        set(value) {
            prefs.edit().putString("imap_password", value).commit()
        }

    var isImapWifiOnly: Boolean
        get() = prefs.getBoolean("imap_wifi_only", false)
        set(value) {
            prefs.edit().putBoolean("imap_wifi_only", value).commit()
        }

    var lastEmailCheckTimestamp: Long
        get() = prefs.getLong("last_email_check_ts", 0L)
        set(value) {
            prefs.edit().putLong("last_email_check_ts", value).commit()
        }

    // --- Custom Wake Word & Picovoice Porcupine Encrypted Credentials ---

    var isWakeWordEnabled: Boolean
        get() = prefs.getBoolean("wake_word_enabled", false)
        set(value) {
            prefs.edit().putBoolean("wake_word_enabled", value).commit()
        }

    var wakeWordName: String
        get() = prefs.getString("custom_wake_word_name", "")?.trim().orEmpty()
        set(value) {
            prefs.edit().putString("custom_wake_word_name", value.trim()).commit()
        }

    var picovoiceAccessKey: String
        get() = sanitizeApiKey(prefs.getString("picovoice_access_key", null))
        set(value) {
            prefs.edit().putString("picovoice_access_key", sanitizeApiKey(value)).commit()
        }

    fun hasPicovoiceKey(): Boolean = picovoiceAccessKey.isNotBlank()

    fun maskedPicovoiceKeyPreview(): String {
        val key = picovoiceAccessKey
        if (key.isBlank()) return "None saved"
        return if (key.length <= 8) {
            "Saved (${key.length} chars)"
        } else {
            "${key.take(4)}...${key.takeLast(4)} (${key.length} chars)"
        }
    }
}
