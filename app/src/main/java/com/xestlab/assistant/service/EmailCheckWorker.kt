package com.xestlab.assistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xestlab.assistant.R
import com.xestlab.assistant.data.SecurePreferences
import com.xestlab.assistant.data.UserPreferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Session
import javax.mail.search.FlagTerm

class EmailCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val securePrefs = SecurePreferences(applicationContext)
        if (!securePrefs.isImapEnabled || securePrefs.imapUser.isBlank() || securePrefs.imapPassword.isBlank()) {
            return@withContext Result.success()
        }

        val prefs = UserPreferencesDataStore(applicationContext).getSnapshot()
        if (AlarmScheduler.isWithinQuietHours(prefs)) {
            return@withContext Result.success()
        }

        val unreadResult = EmailHelper.fetchUnreadSummary(
            host = securePrefs.imapHost,
            port = securePrefs.imapPort,
            user = securePrefs.imapUser,
            password = securePrefs.imapPassword
        )

        unreadResult.onSuccess { summary ->
            securePrefs.lastEmailCheckTimestamp = System.currentTimeMillis()
            if (summary.unreadCount > 0 && summary.latestSubject != null) {
                postEmailNotification(summary.unreadCount, summary.latestSubject)
            }
        }

        Result.success()
    }

    private fun postEmailNotification(unreadCount: Int, latestSubject: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "xestlab_email_channel"
        val channel = NotificationChannel(
            channelId,
            "Important Email Checks",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("$unreadCount unread inbox ${if (unreadCount == 1) "message" else "messages"}")
            .setContentText("Latest: $latestSubject")
            .setAutoCancel(true)
            .build()

        try {
            manager.notify(5200, notification)
        } catch (_: SecurityException) {
        }
    }
}

data class UnreadEmailSummary(
    val unreadCount: Int,
    val latestSubject: String?
)

object EmailHelper {

    private const val WORK_NAME = "xestlab_imap_periodic_work"

    fun schedulePeriodicChecks(context: Context, wifiOnly: Boolean) {
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresBatteryNotLow(true)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<EmailCheckWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }

    fun cancelPeriodicChecks(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    suspend fun testImapConnection(
        host: String,
        port: Int,
        user: String,
        password: String
    ): Result<String> = withContext(Dispatchers.IO) {
        fetchUnreadSummary(host, port, user, password).map { summary ->
            "Connected to $host (${summary.unreadCount} unread in INBOX)."
        }
    }

    fun fetchUnreadSummary(
        host: String,
        port: Int,
        user: String,
        password: String
    ): Result<UnreadEmailSummary> {
        var store: javax.mail.Store? = null
        var inbox: Folder? = null
        return try {
            val props = Properties().apply {
                put("mail.store.protocol", "imaps")
                put("mail.imaps.host", host.trim())
                put("mail.imaps.port", port.toString())
                put("mail.imaps.ssl.enable", "true")
                put("mail.imaps.connectiontimeout", "8000")
                put("mail.imaps.timeout", "10000")
            }
            val session = Session.getInstance(props)
            store = session.getStore("imaps")
            store.connect(host.trim(), port, user.trim(), password)

            inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)

            val unseen = inbox.search(FlagTerm(Flags(Flags.Flag.SEEN), false))
            val latestSubject = unseen.lastOrNull()?.subject
            Result.success(
                UnreadEmailSummary(
                    unreadCount = unseen.size,
                    latestSubject = latestSubject
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                inbox?.close(false)
            } catch (_: Exception) {
            }
            try {
                store?.close()
            } catch (_: Exception) {
            }
        }
    }
}
