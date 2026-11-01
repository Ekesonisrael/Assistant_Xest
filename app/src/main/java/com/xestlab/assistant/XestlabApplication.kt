package com.xestlab.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.xestlab.assistant.data.SecurePreferences
import com.xestlab.assistant.data.UserPreferencesDataStore
import com.xestlab.assistant.data.local.AppDatabase
import com.xestlab.assistant.service.AlarmReceiver
import com.xestlab.assistant.service.AlarmScheduler
import com.xestlab.assistant.service.EmailHelper
import com.xestlab.assistant.service.VoiceReminderService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class XestlabApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()

        appScope.launch {
            val db = AppDatabase.getInstance(this@XestlabApplication)
            AppDatabase.ensureSeeded(db)

            val userPrefs = UserPreferencesDataStore(this@XestlabApplication).getSnapshot()
            if (userPrefs.isFirstLaunchComplete) {
                val schedules = db.reminderDao().getAllRemindersDirect()
                val pending = db.itemDao().getPendingItemsDirect()
                AlarmScheduler.scheduleAllActive(
                    context = this@XestlabApplication,
                    wakeUpTime = userPrefs.wakeUpTime,
                    schedules = schedules,
                    pendingItems = pending
                )
            }

            val securePrefs = SecurePreferences(this@XestlabApplication)
            if (securePrefs.isImapEnabled && securePrefs.imapUser.isNotBlank()) {
                EmailHelper.schedulePeriodicChecks(this@XestlabApplication, securePrefs.isImapWifiOnly)
            }
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val voiceChannel = NotificationChannel(
            VoiceReminderService.CHANNEL_ID,
            "Voice Briefings",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Spoken daily plan briefings via Android TextToSpeech"
        }

        val alertsChannel = NotificationChannel(
            AlarmReceiver.ALERT_CHANNEL_ID,
            "Reminders and Briefings",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Scheduled briefings, task reminders, and person follow-up alerts"
        }

        manager.createNotificationChannel(voiceChannel)
        manager.createNotificationChannel(alertsChannel)
    }
}
