package com.xestlab.assistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.xestlab.assistant.data.SecurePreferences
import com.xestlab.assistant.data.local.AppDatabase
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.mail.*
import javax.mail.search.FlagTerm

object EmailHelper {

    private const val TAG = "EmailHelper"
    private const val CHANNEL_ID = "xestlab_email_channel"
    private const val NOTIF_ID = 2001

    data class EmailSummary(
        val subject: String,
        val from: String,
        val isUrgent: Boolean,
        val date: Date
    )

    fun testImapConnection(
        host: String,
        port: Int,
        user: String,
        pass: String
    ): Pair<Boolean, String> {
        return try {
            val props = Properties().apply {
                put("mail.imap.ssl.enable", "true")
                put("mail.imap.connectiontimeout", "5000")
                put("mail.imap.timeout", "5000")
            }
            val session = Session.getInstance(props)
            val store = session.getStore("imaps")
            store.connect(host, port, user, pass)
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)
            val unreadCount = inbox.unreadMessageCount
            inbox.close(false)
            store.close()
            Pair(true, "IMAP connected successfully ($unreadCount unread messages).")
        } catch (e: Exception) {
            Log.e(TAG, "IMAP connection failed", e)
            Pair(false, e.message ?: "Failed to connect to IMAP server")
        }
    }

    fun checkUnreadEmails(
        host: String,
        port: Int,
        user: String,
        pass: String
    ): List<EmailSummary> {
        val list = mutableListOf<EmailSummary>()
        var store: Store? = null
        var inbox: Folder? = null
        try {
            val props = Properties().apply {
                put("mail.imap.ssl.enable", "true")
                put("mail.imap.connectiontimeout", "6000")
                put("mail.imap.timeout", "6000")
            }
            val session = Session.getInstance(props)
            store = session.getStore("imaps")
            store.connect(host, port, user, pass)
            inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)

            // Search unread messages
            val unreadFlag = FlagTerm(Flags(Flags.Flag.SEEN), false)
            val messages = inbox.search(unreadFlag)

            // Take last 5 unread
            val recent = messages.takeLast(5)
            for (msg in recent) {
                val subject = msg.subject ?: "(No Subject)"
                val from = msg.from?.firstOrNull()?.toString() ?: "Unknown sender"
                val isUrgent = isUrgentSubject(subject)
                val date = msg.sentDate ?: Date()
                list.add(EmailSummary(subject, from, isUrgent, date))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking unread emails", e)
        } finally {
            try {
                inbox?.close(false)
                store?.close()
            } catch (_: Exception) {}
        }
        return list
    }

    private fun isUrgentSubject(subject: String): Boolean {
        val lower = subject.lowercase(Locale.ROOT)
        return lower.contains("urgent") ||
                lower.contains("action required") ||
                lower.contains("quote") ||
                lower.contains("contract") ||
                lower.contains("payment") ||
                lower.contains("invoice") ||
                lower.contains("asap")
    }

    fun postEmailNotification(context: Context, emails: List<EmailSummary>) {
        if (emails.isEmpty()) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel(context, manager)

        val urgentOnes = emails.filter { it.isUrgent }
        val title = if (urgentOnes.isNotEmpty()) {
            "Priority Email: ${urgentOnes.first().from}"
        } else {
            "${emails.size} new unread email(s)"
        }

        val body = emails.joinToString("\n") { "• ${it.subject} (from ${it.from})" }

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(emails.first().subject)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (urgentOnes.isNotEmpty()) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIF_ID, notif)
    }

    private fun createChannel(context: Context, manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Email Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Priority alerts for incoming unread emails"
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun scheduleWork(context: Context, wifiOnly: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<EmailCheckWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "xestlab_email_check_work",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelWork(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("xestlab_email_check_work")
    }
}

class EmailCheckWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val secure = SecurePreferences(appContext)
        if (!secure.isImapEnabled || secure.imapUser.isBlank() || secure.imapPassword.isBlank()) {
            return Result.success()
        }

        try {
            val emails = EmailHelper.checkUnreadEmails(
                host = secure.imapHost,
                port = secure.imapPort,
                user = secure.imapUser,
                pass = secure.imapPassword
            )

            if (emails.isNotEmpty()) {
                EmailHelper.postEmailNotification(appContext, emails)
            }
            return Result.success()
        } catch (e: Exception) {
            return Result.retry()
        }
    }
}
