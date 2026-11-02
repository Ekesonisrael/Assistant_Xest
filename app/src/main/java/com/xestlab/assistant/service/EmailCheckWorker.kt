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
import com.xestlab.assistant.ai.AiEmailBatchInput
import com.xestlab.assistant.data.SecurePreferences
import com.xestlab.assistant.data.local.AppDatabase
import com.xestlab.assistant.data.model.EmailSenderRuleEntity
import com.xestlab.assistant.data.model.UnreadEmailEntity
import com.xestlab.assistant.data.model.learnedKeywordsList
import com.xestlab.assistant.data.model.tagsList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
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

    private val _lastCheckTimeFlow = MutableStateFlow(0L)
    val lastCheckTimeFlow: StateFlow<Long> = _lastCheckTimeFlow.asStateFlow()

    private val _lastCheckResultFlow = MutableStateFlow("Never checked")
    val lastCheckResultFlow: StateFlow<String> = _lastCheckResultFlow.asStateFlow()

    private val _lastCheckIsErrorFlow = MutableStateFlow(false)
    val lastCheckIsErrorFlow: StateFlow<Boolean> = _lastCheckIsErrorFlow.asStateFlow()

    fun syncPersistedHealth(securePrefs: SecurePreferences) {
        _lastCheckTimeFlow.value = securePrefs.lastEmailCheckTimestamp
        _lastCheckResultFlow.value = securePrefs.lastEmailCheckResult
        _lastCheckIsErrorFlow.value = securePrefs.lastEmailCheckIsError
    }

    private fun recordHealthCheck(
        securePrefs: SecurePreferences,
        timestampMillis: Long,
        resultText: String,
        isError: Boolean
    ) {
        securePrefs.lastEmailCheckTimestamp = timestampMillis
        securePrefs.lastEmailCheckResult = resultText
        securePrefs.lastEmailCheckIsError = isError
        _lastCheckTimeFlow.value = timestampMillis
        _lastCheckResultFlow.value = resultText
        _lastCheckIsErrorFlow.value = isError
    }

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
        fetchRawUnreadAndSentSnapshot(host, port, user, password).map { snapshot ->
            "Connected to $host (${snapshot.unreadMessages.size} recent unread in INBOX)."
        }
    }

    /**
     * Fetches unread IMAP emails strictly in READ_ONLY mode (never marks, moves, or deletes on server),
     * classifies every unread email as HIGH, NORMAL, or LOW using only sender, subject, and short snippet,
     * detects dates/times & calendar invites, and sends notifications ONLY for HIGH emails.
     */
    suspend fun syncUnreadEmailsAndNotify(
        context: Context,
        postNotifications: Boolean = true
    ): Result<List<UnreadEmailEntity>> = withContext(Dispatchers.IO) {
        val securePrefs = SecurePreferences(context)
        val nowMs = System.currentTimeMillis()
        if (securePrefs.imapUser.isBlank() || securePrefs.imapPassword.isBlank()) {
            val msg = "IMAP email or password not saved."
            recordHealthCheck(securePrefs, nowMs, msg, isError = true)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        val rawResult = fetchRawUnreadAndSentSnapshot(
            host = securePrefs.imapHost,
            port = securePrefs.imapPort,
            user = securePrefs.imapUser,
            password = securePrefs.imapPassword
        )

        rawResult.fold(
            onSuccess = { snapshot ->
                val rawEmails = snapshot.unreadMessages
                val emailedRecipients = snapshot.emailedRecipientAddresses
                val db = AppDatabase.getInstance(context)

                // Update sender rules with contacts King Israel has emailed before
                val existingRulesList = db.emailRuleDao().getAllSenderRulesDirect()
                val rulesByKey = existingRulesList.associateBy { it.senderKey }.toMutableMap()

                for (raw in rawEmails) {
                    val key = EmailPriorityEngine.normalizeSenderKey(raw.senderAddress, raw.sender)
                    if (key.isNotBlank() && raw.senderAddress.isNotBlank() && raw.senderAddress in emailedRecipients) {
                        val prevRule = rulesByKey[key]
                        if (prevRule == null) {
                            val newRule = EmailSenderRuleEntity(
                                senderKey = key,
                                senderAddress = raw.senderAddress,
                                senderDisplayName = raw.sender,
                                priorityOverride = "AUTO",
                                hasEmailedBefore = true,
                                updatedAt = System.currentTimeMillis()
                            )
                            db.emailRuleDao().upsertSenderRule(newRule)
                            rulesByKey[key] = newRule
                        } else if (!prevRule.hasEmailedBefore) {
                            val updated = prevRule.copy(hasEmailedBefore = true)
                            db.emailRuleDao().upsertSenderRule(updated)
                            rulesByKey[key] = updated
                        }
                    }
                }

                val existingById = db.emailDao().getRecentUnreadDirect(100).associateBy { it.id }

                val prioritySenderLabels = rulesByKey.values
                    .filter { it.priorityOverride == "HIGH" }
                    .map { it.senderAddress.ifBlank { it.senderDisplayName } }
                val lowSenderLabels = rulesByKey.values
                    .filter { it.priorityOverride == "LOW" || it.dismissCount >= 3 }
                    .map { it.senderAddress.ifBlank { it.senderDisplayName } }
                val emailedContactLabels = (
                    emailedRecipients + rulesByKey.values.filter { it.hasEmailedBefore }.map { it.senderKey }
                    ).distinct()
                val learnedKeywordsSet = rulesByKey.values
                    .filter { it.priorityOverride == "HIGH" || it.positiveCount > 0 }
                    .flatMap { it.learnedKeywordsList }
                    .toSet()

                // Batch AI classification for newly arrived unread emails without manual overrides
                val newCandidatesForAi = rawEmails.filter { raw ->
                    val prior = existingById[raw.id]
                    val key = EmailPriorityEngine.normalizeSenderKey(raw.senderAddress, raw.sender)
                    val rule = rulesByKey[key]
                    val hasExplicitOverride = rule != null &&
                        (rule.priorityOverride in setOf("HIGH", "NORMAL", "LOW") || rule.dismissCount >= 3)
                    prior == null && !hasExplicitOverride
                }.map { raw ->
                    val senderForAi = if (raw.senderAddress.isNotBlank() &&
                        !raw.sender.contains(raw.senderAddress, ignoreCase = true)
                    ) {
                        "${raw.sender} <${raw.senderAddress}>"
                    } else {
                        raw.sender
                    }
                    AiEmailBatchInput(
                        id = raw.id,
                        sender = senderForAi,
                        subject = raw.subject,
                        snippet = raw.snippet.take(160)
                    )
                }

                val aiClassifications = if (
                    newCandidatesForAi.isNotEmpty() &&
                    securePrefs.isAiEnabled &&
                    securePrefs.hasGroqKey()
                ) {
                    AiClient.classifyEmailsBatchViaGroq(
                        inputs = newCandidatesForAi,
                        prioritySenders = prioritySenderLabels,
                        lowSenders = lowSenderLabels,
                        emailedContacts = emailedContactLabels,
                        learnedKeywords = learnedKeywordsSet.toList(),
                        securePrefs = securePrefs
                    )
                } else {
                    emptyMap()
                }

                val syncTime = System.currentTimeMillis()
                val entities = rawEmails.map { raw ->
                    val prior = existingById[raw.id]
                    val senderKey = EmailPriorityEngine.normalizeSenderKey(raw.senderAddress, raw.sender)
                    val rule = rulesByKey[senderKey]
                    val hasUnsubOrNoReply = raw.hasUnsubscribeHeader ||
                        EmailPriorityEngine.isNoReplyAddress(raw.senderAddress, raw.sender)

                    if (prior != null) {
                        // Preserve user's tags, dismissedInApp, addedToTasks, notified, reminderCreated
                        val userTaggedLow = prior.tagsList.any { it.equals("Low", ignoreCase = true) }
                        val ruleForcedDecision = if (
                            rule != null &&
                            (rule.priorityOverride in setOf("HIGH", "NORMAL", "LOW") || rule.dismissCount >= 3)
                        ) {
                            EmailPriorityEngine.classifyOffline(
                                senderDisplay = raw.sender,
                                senderAddress = raw.senderAddress,
                                subject = raw.subject,
                                snippet = raw.snippet,
                                hasUnsubscribeHeader = raw.hasUnsubscribeHeader,
                                isReplyHeader = raw.isReplyHeader,
                                isCalendarInvite = raw.isCalendarInvite,
                                senderRule = rule,
                                allLearnedKeywords = learnedKeywordsSet
                            )
                        } else {
                            null
                        }

                        val effectivePriority = when {
                            ruleForcedDecision != null -> ruleForcedDecision.priority
                            userTaggedLow -> "LOW"
                            prior.priority in setOf("HIGH", "NORMAL", "LOW") -> prior.priority
                            else -> "NORMAL"
                        }
                        val effectiveReason = when {
                            ruleForcedDecision != null -> ruleForcedDecision.reason
                            userTaggedLow -> "Tagged Low by you"
                            prior.priorityReason.isNotBlank() -> prior.priorityReason
                            else -> "Unread inbox message"
                        }

                        prior.copy(
                            sender = raw.sender,
                            senderAddress = raw.senderAddress,
                            subject = raw.subject,
                            snippet = raw.snippet,
                            receivedAt = raw.receivedAt,
                            priority = effectivePriority,
                            priorityReason = effectiveReason,
                            isActionable = effectivePriority == "HIGH" || prior.isActionable,
                            hasUnsubscribeOrNoReply = hasUnsubOrNoReply,
                            isCalendarInvite = raw.isCalendarInvite || prior.isCalendarInvite,
                            eventTimeMillis = raw.eventTimeMillis ?: prior.eventTimeMillis,
                            fetchedAt = syncTime
                        )
                    } else {
                        val decision = aiClassifications[raw.id] ?: EmailPriorityEngine.classifyOffline(
                            senderDisplay = raw.sender,
                            senderAddress = raw.senderAddress,
                            subject = raw.subject,
                            snippet = raw.snippet,
                            hasUnsubscribeHeader = raw.hasUnsubscribeHeader,
                            isReplyHeader = raw.isReplyHeader,
                            isCalendarInvite = raw.isCalendarInvite,
                            senderRule = rule,
                            allLearnedKeywords = learnedKeywordsSet
                        )
                        UnreadEmailEntity(
                            id = raw.id,
                            sender = raw.sender,
                            senderAddress = raw.senderAddress,
                            subject = raw.subject,
                            snippet = raw.snippet,
                            receivedAt = raw.receivedAt,
                            isActionable = decision.isActionable || decision.priority == "HIGH",
                            category = decision.category,
                            extractedTaskTitle = decision.extractedTaskTitle,
                            addedToTasks = false,
                            notified = false,
                            fetchedAt = syncTime,
                            priority = decision.priority,
                            priorityReason = decision.reason,
                            tagsCsv = "",
                            dismissedInApp = false,
                            hasUnsubscribeOrNoReply = hasUnsubOrNoReply,
                            isCalendarInvite = raw.isCalendarInvite,
                            eventTimeMillis = raw.eventTimeMillis,
                            reminderCreated = false
                        )
                    }
                }

                if (entities.isEmpty()) {
                    db.emailDao().clearAllEmails()
                } else {
                    db.emailDao().upsertEmails(entities)
                    db.emailDao().pruneSyncedUnread(entities.map { it.id })
                }

                // STRICT RULE: Only HIGH priority emails send a notification
                if (postNotifications) {
                    entities.filter {
                        it.priority == "HIGH" &&
                            !it.notified &&
                            !it.dismissedInApp &&
                            !it.addedToTasks
                    }
                        .take(5)
                        .forEach { highEmail ->
                            postHighPriorityEmailNotification(context, highEmail)
                            db.emailDao().markNotified(highEmail.id)
                        }
                }

                val visible = entities.filter { !it.dismissedInApp }
                val highCount = visible.count { it.priority == "HIGH" }
                val normalCount = visible.count { it.priority == "NORMAL" }
                val lowCount = visible.count { it.priority == "LOW" }
                val summaryMsg =
                    "OK — ${visible.size} unread ($highCount High, $normalCount Normal, $lowCount Low)"
                recordHealthCheck(securePrefs, syncTime, summaryMsg, isError = false)

                Result.success(entities)
            },
            onFailure = { err ->
                val errSummary = "Error: ${err.javaClass.simpleName}: ${err.message ?: "IMAP connection failed"}"
                recordHealthCheck(securePrefs, System.currentTimeMillis(), errSummary, isError = true)
                Result.failure(err)
            }
        )
    }

    private fun postHighPriorityEmailNotification(context: Context, email: UnreadEmailEntity) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            EMAIL_CHANNEL_ID,
            "High-Priority Unread Emails",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for High-priority unread emails only"
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
            putExtra(EmailTaskActionReceiver.EXTRA_SENDER_ADDRESS, email.senderAddress)
            putExtra(EmailTaskActionReceiver.EXTRA_SUBJECT, email.subject)
            putExtra(EmailTaskActionReceiver.EXTRA_SNIPPET, email.snippet)
        }
        val addTaskPending = PendingIntent.getBroadcast(
            context,
            notifId + 1,
            addTaskIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val headerLabel = when (email.category) {
            "INVOICE" -> "High Priority Invoice: ${email.sender}"
            "MEETING" -> "High Priority Invite: ${email.sender}"
            else -> "High Priority Email: ${email.sender}"
        }

        val detailLines = buildString {
            append(email.subject)
            if (email.priorityReason.isNotBlank()) {
                append("\nWhy: ${email.priorityReason}")
            }
            if (email.snippet.isNotBlank()) {
                append("\n${email.snippet}")
            }
        }

        val notification = NotificationCompat.Builder(context, EMAIL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(headerLabel)
            .setContentText(email.subject)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detailLines.trim()))
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
        val senderAddress: String,
        val subject: String,
        val snippet: String,
        val receivedAt: Long,
        val hasUnsubscribeHeader: Boolean,
        val isReplyHeader: Boolean,
        val isCalendarInvite: Boolean,
        val eventTimeMillis: Long?
    )

    private data class ImapSyncSnapshot(
        val unreadMessages: List<RawImapEmail>,
        val emailedRecipientAddresses: Set<String>
    )

    /**
     * Connects to IMAPS strictly READ_ONLY: never marks read, moves, or deletes any message.
     */
    private fun fetchRawUnreadAndSentSnapshot(
        host: String,
        port: Int,
        user: String,
        password: String
    ): Result<ImapSyncSnapshot> {
        var store: Store? = null
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

            val emailedAddresses = fetchRecentSentRecipientsReadOnly(store)

            inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)

            val unseen = inbox.search(FlagTerm(Flags(Flags.Flag.SEEN), false))
            val latestSlice = unseen.takeLast(40).reversed()

            val mapped = latestSlice.map { msg ->
                val fromAddr = (msg.from?.firstOrNull() as? InternetAddress)
                val senderAddrClean = fromAddr?.address?.trim()?.lowercase(Locale.US).orEmpty()
                val senderClean = fromAddr?.personal?.takeIf { it.isNotBlank() }
                    ?: senderAddrClean.ifBlank { "Unknown Sender" }
                val subjectClean = msg.subject?.trim()?.takeIf { it.isNotBlank() } ?: "(No Subject)"
                val receivedMillis = msg.receivedDate?.time ?: msg.sentDate?.time ?: System.currentTimeMillis()
                val snippetClean = extractPlainTextSnippet(msg).take(180)
                val msgIdHeader = msg.getHeader("Message-ID")?.firstOrNull()
                    ?: "$senderClean|$subjectClean|$receivedMillis"

                val hasUnsubHeader = !msg.getHeader("List-Unsubscribe").isNullOrEmpty() ||
                    !msg.getHeader("List-Unsubscribe-Post").isNullOrEmpty()
                val isReply = !msg.getHeader("In-Reply-To").isNullOrEmpty() ||
                    !msg.getHeader("References").isNullOrEmpty() ||
                    subjectClean.startsWith("re:", ignoreCase = true)

                val calendarIcsText = extractCalendarIcsContent(msg)
                val icsTime = calendarIcsText?.let { EmailPriorityEngine.parseIcsStartMillis(it) }
                val isInvite = calendarIcsText != null ||
                    subjectClean.startsWith("invitation:", ignoreCase = true) ||
                    subjectClean.startsWith("updated invitation:", ignoreCase = true) ||
                    subjectClean.contains(".ics", ignoreCase = true)
                val parsedEventMillis = icsTime ?: EmailPriorityEngine.detectDateTimeInText(
                    subject = subjectClean,
                    snippet = snippetClean,
                    referenceMillis = receivedMillis
                )

                RawImapEmail(
                    id = sha256Short(msgIdHeader),
                    sender = senderClean,
                    senderAddress = senderAddrClean,
                    subject = subjectClean,
                    snippet = snippetClean,
                    receivedAt = receivedMillis,
                    hasUnsubscribeHeader = hasUnsubHeader,
                    isReplyHeader = isReply,
                    isCalendarInvite = isInvite,
                    eventTimeMillis = parsedEventMillis
                )
            }

            Result.success(ImapSyncSnapshot(mapped, emailedAddresses))
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

    private fun fetchRecentSentRecipientsReadOnly(store: Store): Set<String> {
        val candidateFolders = listOf(
            "[Gmail]/Sent Mail",
            "Sent",
            "Sent Items",
            "Sent Messages",
            "INBOX.Sent"
        )
        val collected = HashSet<String>()
        for (folderName in candidateFolders) {
            var sentFolder: Folder? = null
            try {
                val folder = store.getFolder(folderName)
                if (folder != null && folder.exists()) {
                    sentFolder = folder
                    sentFolder.open(Folder.READ_ONLY)
                    val total = sentFolder.messageCount
                    if (total > 0) {
                        val start = (total - 59).coerceAtLeast(1)
                        val msgs = sentFolder.getMessages(start, total)
                        val fp = FetchProfile().apply { add(FetchProfile.Item.ENVELOPE) }
                        sentFolder.fetch(msgs, fp)
                        for (m in msgs) {
                            m.allRecipients?.forEach { addr ->
                                val email = (addr as? InternetAddress)?.address?.trim()?.lowercase(Locale.US)
                                if (!email.isNullOrBlank()) {
                                    collected.add(email)
                                }
                            }
                        }
                    }
                    break
                }
            } catch (_: Exception) {
                // Ignore if Sent folder name differs on this provider
            } finally {
                try {
                    sentFolder?.close(false)
                } catch (_: Exception) {
                }
            }
        }
        return collected
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
                part.isMimeType("text/html") -> {
                    val html = (part.content as? String).orEmpty()
                    html.replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
                        .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
                        .replace(Regex("<[^>]+>"), " ")
                        .replace("&nbsp;", " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                }
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractCalendarIcsContent(part: Part): String? {
        return try {
            val contentTypeLower = part.contentType?.lowercase(Locale.US).orEmpty()
            val fileNameLower = part.fileName?.lowercase(Locale.US).orEmpty()
            when {
                part.isMimeType("text/calendar") ||
                    contentTypeLower.contains("application/ics") ||
                    fileNameLower.endsWith(".ics") -> {
                    when (val rawContent = part.content) {
                        is String -> rawContent
                        is InputStream -> rawContent.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        else -> null
                    }
                }
                part.isMimeType("multipart/*") -> {
                    val mp = part.content as? Multipart ?: return null
                    for (i in 0 until mp.count) {
                        val found = extractCalendarIcsContent(mp.getBodyPart(i))
                        if (!found.isNullOrBlank()) return found
                    }
                    null
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256Short(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }
}
