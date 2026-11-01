package com.xestlab.assistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xestlab.assistant.MainActivity
import com.xestlab.assistant.R
import com.xestlab.assistant.ai.AiClient
import com.xestlab.assistant.data.SecurePreferences
import com.xestlab.assistant.data.local.AppDatabase
import com.xestlab.assistant.data.model.UnreadEmailEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.InternetAddress
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

        EmailHelper.syncUnreadEmailsAndNotify(
            context = applicationContext,
            postNotifications = true
        )

        Result.success()
    }
}

object EmailHelper {

    private const val WORK_NAME = "xestlab_imap_periodic_work"
    const val EMAIL_CHANNEL_ID = "xestlab_smart_email_channel"

    fun schedulePeriodicChecks(context: Context, wifiOnly: Boolean) {
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
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
        fetchRawUnreadMessages(host, port, user, password).map { rawList ->
            "Connected to $host (${rawList.size} recent unread in INBOX)."
        }
    }

    /**
     * Fetches unread IMAP emails, caches them in Room `unread_emails`, runs AI/heuristic
     * task & invoice extraction, and notifies with an "Add to Tasks" button.
     */
    suspend fun syncUnreadEmailsAndNotify(
        context: Context,
        postNotifications: Boolean = true
    ): Result<List<UnreadEmailEntity>> = withContext(Dispatchers.IO) {
        val securePrefs = SecurePreferences(context)
        if (securePrefs.imapUser.isBlank() || securePrefs.imapPassword.isBlank()) {
            return@withContext Result.failure(IllegalStateException("IMAP email or password not saved."))
        }

        val rawResult = fetchRawUnreadMessages(
            host = securePrefs.imapHost,
            port = securePrefs.imapPort,
            user = securePrefs.imapUser,
            password = securePrefs.imapPassword
        )

        rawResult.map { rawEmails ->
            securePrefs.lastEmailCheckTimestamp = System.currentTimeMillis()
            val db = AppDatabase.getInstance(context)
            val existingById = db.emailDao().getRecentUnreadDirect(50).associateBy { it.id }

            val entities = rawEmails.map { raw ->
                val prior = existingById[raw.id]
                if (prior != null) {
                    prior.copy(fetchedAt = System.currentTimeMillis())
                } else {
                    val analysis = AiClient.analyzeEmailForTaskOrInvoice(
                        sender = raw.sender,
                        subject = raw.subject,
                        snippet = raw.snippet,
                        securePrefs = securePrefs
                    )
                    UnreadEmailEntity(
                        id = raw.id,
                        sender = raw.sender,
                        subject = raw.subject,
                        snippet = raw.snippet,
                        receivedAt = raw.receivedAt,
                        isActionable = analysis.isActionable,
                        category = analysis.category,
                        extractedTaskTitle = analysis.extractedTaskTitle,
                        addedToTasks = false,
                        notified = false,
                        fetchedAt = System.currentTimeMillis()
                    )
                }
            }

            if (entities.isEmpty()) {
                db.emailDao().clearAllEmails()
            } else {
                db.emailDao().upsertEmails(entities)
                db.emailDao().pruneSyncedUnread(entities.map { it.id })
            }

            if (postNotifications) {
                entities.filter { it.isActionable && !it.notified && !it.addedToTasks }
                    .take(3)
                    .forEach { actionableEmail ->
                        postActionableEmailNotification(context, actionableEmail)
                        db.emailDao().markNotified(actionableEmail.id)
                    }
            }

            entities
        }
    }

    private fun postActionableEmailNotification(context: Context, email: UnreadEmailEntity) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            EMAIL_CHANNEL_ID,
            "Smart Email Tasks & Invoices",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Actionable tasks and invoices detected in unread emails"
        }
        manager.createNotificationChannel(channel)

        val notifId = email.id.hashCode()
        val openAppIntent = Intent(context, MainActivity::class.java)
        val openAppPending = PendingIntent.getActivity(
            context,
            notifId,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val addTaskIntent = Intent(context, EmailTaskActionReceiver::class.java).apply {
            action = EmailTaskActionReceiver.ACTION_ADD_EMAIL_TO_TASKS
            putExtra(EmailTaskActionReceiver.EXTRA_NOTIFICATION_ID, notifId)
            putExtra(EmailTaskActionReceiver.EXTRA_EMAIL_ID, email.id)
            putExtra(EmailTaskActionReceiver.EXTRA_TASK_TITLE, email.extractedTaskTitle ?: email.subject)
            putExtra(EmailTaskActionReceiver.EXTRA_SENDER, email.sender)
            putExtra(EmailTaskActionReceiver.EXTRA_SUBJECT, email.subject)
        }
        val addTaskPending = PendingIntent.getBroadcast(
            context,
            notifId + 1,
            addTaskIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val headerLabel = if (email.category == "INVOICE") {
            "Invoice Email: ${email.sender}"
        } else {
            "Email Task: ${email.sender}"
        }

        val notification = NotificationCompat.Builder(context, EMAIL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(headerLabel)
            .setContentText(email.extractedTaskTitle ?: email.subject)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${email.subject}\n${email.snippet}".trim()))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppPending)
            .addAction(0, "Add to Tasks", addTaskPending)
            .setAutoCancel(true)
            .build()

        try {
            manager.notify(notifId, notification)
        } catch (_: SecurityException) {
        }
    }

    private data class RawImapEmail(
        val id: String,
        val sender: String,
        val subject: String,
        val snippet: String,
        val receivedAt: Long
    )

    private fun fetchRawUnreadMessages(
        host: String,
        port: Int,
        user: String,
        password: String
    ): Result<List<RawImapEmail>> {
        var store: javax.mail.Store? = null
        var inbox: Folder? = null
        return try {
            val props = Properties().apply {
                put("mail.store.protocol", "imaps")
                put("mail.imaps.host", host.trim())
                put("mail.imaps.port", port.toString())
                put("mail.imaps.ssl.enable", "true")
                put("mail.imaps.connectiontimeout", "10000")
                put("mail.imaps.timeout", "15000")
            }
            val session = Session.getInstance(props)
            store = session.getStore("imaps")
            store.connect(host.trim(), port, user.trim(), password)

            inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)

            val unseen = inbox.search(FlagTerm(Flags(Flags.Flag.SEEN), false))
            val latestSlice = unseen.takeLast(15).reversed()

            val mapped = latestSlice.map { msg ->
                val fromAddr = (msg.from?.firstOrNull() as? InternetAddress)
                val senderClean = fromAddr?.personal?.takeIf { it.isNotBlank() }
                    ?: fromAddr?.address
                    ?: "Unknown Sender"
                val subjectClean = msg.subject?.trim()?.takeIf { it.isNotBlank() } ?: "(No Subject)"
                val receivedMillis = msg.receivedDate?.time ?: msg.sentDate?.time ?: System.currentTimeMillis()
                val snippetClean = extractPlainTextSnippet(msg).take(220)
                val msgIdHeader = msg.getHeader("Message-ID")?.firstOrNull() ?: "$senderClean|$subjectClean|$receivedMillis"

                RawImapEmail(
                    id = sha256Short(msgIdHeader),
                    sender = senderClean,
                    subject = subjectClean,
                    snippet = snippetClean,
                    receivedAt = receivedMillis
                )
            }

            Result.success(mapped)
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

    private fun extractPlainTextSnippet(part: Part): String {
        return try {
            when {
                part.isMimeType("text/plain") -> {
                    (part.content as? String)?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                }
                part.isMimeType("multipart/*") -> {
                    val mp = part.content as? Multipart ?: return ""
                    for (i in 0 until mp.count) {
                        val bodyPart = mp.getBodyPart(i)
                        val extracted = extractPlainTextSnippet(bodyPart)
                        if (extracted.isNotBlank()) return extracted
                    }
                    ""
                }
                part is Message -> ""
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun sha256Short(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }
}
