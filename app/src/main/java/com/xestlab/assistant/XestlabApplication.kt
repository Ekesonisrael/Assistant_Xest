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
import com.xestlab.assistant.service.WakeWordService
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
            if (securePrefs.isImapEnabled && securePrefs.imapUser.isNotBlank() && securePrefs.imapPassword.isNotBlank()) {
                EmailHelper.schedulePeriodicChecks(this@XestlabApplication, securePrefs.isImapWifiOnly)
            }
            WakeWordService.syncPersistedDiagnostics(this@XestlabApplication)
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val voiceChannel = NotificationChannel(
            VoiceReminderService.CHANNEL_ID,
            "Voice Briefings",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Spoken daily plan briefings via ElevenLabs or Android TextToSpeech"
        }

        val wakeWordChannel = NotificationChannel(
            WakeWordService.CHANNEL_ID,
            "Background Wake Word Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent openWakeWord ONNX listener ('Hey Jarvis')"
            setShowBadge(false)
        }

        val headsUpChannel = NotificationChannel(
            AlarmReceiver.ALERT_CHANNEL_ID,
            "Wake-Up Alarms & Heads-Up Briefings",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "7:30 AM wake-up daily plan briefings and high-priority reminders"
            enableVibration(true)
        }

        val emailChannel = NotificationChannel(
            EmailHelper.EMAIL_CHANNEL_ID,
            "Smart Email Tasks & Invoices",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Actionable tasks and invoices extracted from unread IMAP emails"
        }

        manager.createNotificationChannel(voiceChannel)
        manager.createNotificationChannel(wakeWordChannel)
        manager.createNotificationChannel(headsUpChannel)
        manager.createNotificationChannel(emailChannel)
    }
}
