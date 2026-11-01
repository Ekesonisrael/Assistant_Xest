package com.xestlab.assistant.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "xestlab_assistant_prefs")

data class ReminderState(
    val messageTarget: Int = 10,
    val followUpTarget: Int = 2,
    val alarmHour: Int = 7,
    val alarmMinute: Int = 30,
    val isEnabled: Boolean = true,
    val lastCompletedDate: String = ""
) {
    val isDoneToday: Boolean
        get() {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            return lastCompletedDate == today
        }
}

class ReminderDataStore(private val context: Context) {

    companion object {
        val KEY_MESSAGE_TARGET = intPreferencesKey("message_target")
        val KEY_FOLLOW_UP_TARGET = intPreferencesKey("follow_up_target")
        val KEY_ALARM_HOUR = intPreferencesKey("alarm_hour")
        val KEY_ALARM_MINUTE = intPreferencesKey("alarm_minute")
        val KEY_IS_ENABLED = booleanPreferencesKey("is_enabled")
        val KEY_LAST_COMPLETED_DATE = stringPreferencesKey("last_completed_date")
    }

    val reminderFlow: Flow<ReminderState> = context.dataStore.data.map { prefs ->
        ReminderState(
            messageTarget = prefs[KEY_MESSAGE_TARGET] ?: 10,
            followUpTarget = prefs[KEY_FOLLOW_UP_TARGET] ?: 2,
            alarmHour = prefs[KEY_ALARM_HOUR] ?: 7,
            alarmMinute = prefs[KEY_ALARM_MINUTE] ?: 30,
            isEnabled = prefs[KEY_IS_ENABLED] ?: true,
            lastCompletedDate = prefs[KEY_LAST_COMPLETED_DATE] ?: ""
        )
    }

    suspend fun updateTargets(messages: Int, followUps: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MESSAGE_TARGET] = messages
            prefs[KEY_FOLLOW_UP_TARGET] = followUps
        }
    }

    suspend fun updateAlarmTime(hour: Int, minute: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ALARM_HOUR] = hour
            prefs[KEY_ALARM_MINUTE] = minute
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_IS_ENABLED] = enabled
        }
    }

    suspend fun markDoneToday() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_COMPLETED_DATE] = today
        }
    }
}
