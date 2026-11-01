package com.xestlab.assistant.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.userDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "xestlab_user_preferences"
)

data class UserPreferencesState(
    val userName: String = "",
    val isFirstLaunchComplete: Boolean = false,
    val wakeUpTime: String = "06:30",
    val quietHoursStart: String = "22:00",
    val quietHoursEnd: String = "07:00",
    val quietHoursEnabled: Boolean = true,
    val voiceSpeed: Float = 1.0f,
    val autoReadPlanOnLaunch: Boolean = true
)

class UserPreferencesDataStore(private val context: Context) {

    companion object {
        val KEY_USER_NAME = stringPreferencesKey("user_name")
        val KEY_FIRST_LAUNCH_COMPLETE = booleanPreferencesKey("first_launch_complete")
        val KEY_WAKE_UP_TIME = stringPreferencesKey("wake_up_time")
        val KEY_QUIET_START = stringPreferencesKey("quiet_hours_start")
        val KEY_QUIET_END = stringPreferencesKey("quiet_hours_end")
        val KEY_QUIET_ENABLED = booleanPreferencesKey("quiet_hours_enabled")
        val KEY_VOICE_SPEED = floatPreferencesKey("voice_speed")
        val KEY_AUTO_READ_PLAN = booleanPreferencesKey("auto_read_plan_on_launch")
    }

    val preferencesFlow: Flow<UserPreferencesState> = context.userDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            UserPreferencesState(
                userName = prefs[KEY_USER_NAME] ?: "",
                isFirstLaunchComplete = prefs[KEY_FIRST_LAUNCH_COMPLETE] ?: false,
                wakeUpTime = prefs[KEY_WAKE_UP_TIME] ?: "06:30",
                quietHoursStart = prefs[KEY_QUIET_START] ?: "22:00",
                quietHoursEnd = prefs[KEY_QUIET_END] ?: "07:00",
                quietHoursEnabled = prefs[KEY_QUIET_ENABLED] ?: true,
                voiceSpeed = prefs[KEY_VOICE_SPEED] ?: 1.0f,
                autoReadPlanOnLaunch = prefs[KEY_AUTO_READ_PLAN] ?: true
            )
        }

    suspend fun getSnapshot(): UserPreferencesState {
        return preferencesFlow.first()
    }

    suspend fun completeFirstLaunch(userName: String, wakeUpTime: String = "06:30") {
        val cleanName = userName.trim()
        if (cleanName.isBlank()) return
        context.userDataStore.edit { prefs ->
            prefs[KEY_USER_NAME] = cleanName
            prefs[KEY_WAKE_UP_TIME] = wakeUpTime.ifBlank { "06:30" }
            prefs[KEY_FIRST_LAUNCH_COMPLETE] = true
        }
    }

    suspend fun saveUserName(userName: String) {
        val cleanName = userName.trim()
        if (cleanName.isBlank()) return
        context.userDataStore.edit { prefs ->
            prefs[KEY_USER_NAME] = cleanName
        }
    }

    suspend fun updateProfileAndHours(
        userName: String,
        wakeUpTime: String,
        quietHoursStart: String,
        quietHoursEnd: String,
        quietHoursEnabled: Boolean,
        voiceSpeed: Float,
        autoReadPlanOnLaunch: Boolean
    ) {
        context.userDataStore.edit { prefs ->
            if (userName.isNotBlank()) {
                prefs[KEY_USER_NAME] = userName.trim()
            }
            if (wakeUpTime.isNotBlank()) {
                prefs[KEY_WAKE_UP_TIME] = wakeUpTime.trim()
            }
            prefs[KEY_QUIET_START] = quietHoursStart.trim().ifBlank { "22:00" }
            prefs[KEY_QUIET_END] = quietHoursEnd.trim().ifBlank { "07:00" }
            prefs[KEY_QUIET_ENABLED] = quietHoursEnabled
            prefs[KEY_VOICE_SPEED] = voiceSpeed.coerceIn(0.6f, 1.8f)
            prefs[KEY_AUTO_READ_PLAN] = autoReadPlanOnLaunch
        }
    }

    suspend fun saveWakeUpTime(wakeUpTime: String) {
        context.userDataStore.edit { prefs ->
            prefs[KEY_WAKE_UP_TIME] = wakeUpTime.trim().ifBlank { "06:30" }
        }
    }

    suspend fun saveVoiceSpeed(speed: Float) {
        context.userDataStore.edit { prefs ->
            prefs[KEY_VOICE_SPEED] = speed.coerceIn(0.6f, 1.8f)
        }
    }
}
