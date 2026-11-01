package com.xestlab.assistant.ui.viewmodel

import android.app.Application
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xestlab.assistant.ai.AiClient
import com.xestlab.assistant.ai.AssistantParser
import com.xestlab.assistant.ai.ParsedIntent
import com.xestlab.assistant.ai.PlannerEngine
import com.xestlab.assistant.ai.PlannerProposal
import com.xestlab.assistant.data.SecurePreferences
import com.xestlab.assistant.data.UserPreferencesDataStore
import com.xestlab.assistant.data.UserPreferencesState
import com.xestlab.assistant.data.local.AppDatabase
import com.xestlab.assistant.data.model.ChecklistItem
import com.xestlab.assistant.data.model.ConversationTurn
import com.xestlab.assistant.data.model.DailyLog
import com.xestlab.assistant.data.model.Fact
import com.xestlab.assistant.data.model.Goal
import com.xestlab.assistant.data.model.Item
import com.xestlab.assistant.data.model.ReminderSchedule
import com.xestlab.assistant.data.model.UserSettings
import com.xestlab.assistant.data.repository.AssistantRepository
import com.xestlab.assistant.data.repository.WeeklySummaryData
import com.xestlab.assistant.service.AlarmScheduler
import com.xestlab.assistant.service.EmailHelper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class AssistantViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {

    private val appContext = application.applicationContext
    val userPreferencesDataStore = UserPreferencesDataStore(appContext)
    private val db = AppDatabase.getInstance(appContext)
    val repository = AssistantRepository(db, userPreferencesDataStore)
    val securePrefs = SecurePreferences(appContext)

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var pendingSpeechText: String? = null
    private var hasAutoReadPlanInSession = false

    // Null only while initial DataStore read is in flight so MainActivity routes without flicker
    val userPrefsLoaded: StateFlow<UserPreferencesState?> = userPreferencesDataStore.preferencesFlow
        .map<UserPreferencesState, UserPreferencesState?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val prefsState: StateFlow<UserPreferencesState> = userPreferencesDataStore.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferencesState())

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _dailyPlanSummary = MutableStateFlow("")
    val dailyPlanSummary: StateFlow<String> = _dailyPlanSummary.asStateFlow()

    private val _activeProposal = MutableStateFlow<PlannerProposal?>(null)
    val activeProposal: StateFlow<PlannerProposal?> = _activeProposal.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _noteSearchQuery = MutableStateFlow("")
    val noteSearchQuery: StateFlow<String> = _noteSearchQuery.asStateFlow()

    private val _weeklySummary = MutableStateFlow(
        WeeklySummaryData(
            messagesCount = 0,
            messagesTarget = 50,
            conversationsCount = 0,
            conversationsTarget = 5,
            quotesCount = 0,
            quotesTarget = 3,
            contentCount = 0,
            studyCount = 0
        )
    )
    val weeklySummary: StateFlow<WeeklySummaryData> = _weeklySummary.asStateFlow()

    val todayDateString: String
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    val goals: StateFlow<Goal> = repository.goals
        .map { it ?: Goal() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Goal())

    val checklistItems: StateFlow<List<ChecklistItem>> = repository.checklistItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val todayLogs: StateFlow<List<DailyLog>> = repository.getLogsForDate(todayDateString)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val reminders: StateFlow<List<ReminderSchedule>> = repository.reminders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allItems: StateFlow<List<Item>> = repository.allItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingItems: StateFlow<List<Item>> = repository.pendingItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allFacts: StateFlow<List<Fact>> = repository.allFacts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val conversationTurns: StateFlow<List<ConversationTurn>> = repository.conversationTurns
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val filteredNotes: StateFlow<List<Item>> = _noteSearchQuery
        .flatMapLatest { query -> repository.searchNotes(query) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        tts = TextToSpeech(appContext, this)
        viewModelScope.launch {
            AppDatabase.ensureSeeded(db)
            refreshDailyPlanText()
            refreshWeeklySummary()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            val langRes = engine.setLanguage(Locale.getDefault())
            if (langRes == TextToSpeech.LANG_MISSING_DATA || langRes == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.US)
            }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                }

                override fun onDone(utteranceId: String?) {
                    _isSpeaking.value = false
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _isSpeaking.value = false
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    _isSpeaking.value = false
                }
            })
            isTtsReady = true
            pendingSpeechText?.let { queued ->
                pendingSpeechText = null
                speakText(queued)
            }
        }
    }

    override fun onCleared() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        super.onCleared()
    }

    fun clearStatusBanner() {
        _statusBanner.value = null
    }

    fun setStatusBanner(message: String) {
        _statusBanner.value = message
    }

    /**
     * Saves the user's name on first launch into Jetpack DataStore, schedules proactive alarms,
     * and reads out the personalized daily plan via Android TextToSpeech.
     */
    fun completeFirstLaunch(userName: String, wakeUpTime: String) {
        val trimmedName = userName.trim()
        if (trimmedName.isBlank()) return
        val cleanWake = wakeUpTime.trim().ifBlank { "06:30" }

        viewModelScope.launch {
            userPreferencesDataStore.completeFirstLaunch(trimmedName, cleanWake)
            db.settingsDao().saveSettings(
                UserSettings(
                    id = 1,
                    userName = trimmedName,
                    wakeUpTime = cleanWake,
                    isFirstRunComplete = true
                )
            )
            val schedules = db.reminderDao().getAllRemindersDirect()
            val pending = db.itemDao().getPendingItemsDirect()
            AlarmScheduler.scheduleAllActive(appContext, cleanWake, schedules, pending)

            hasAutoReadPlanInSession = true
            val script = repository.buildSpokenDailyPlan()
            _dailyPlanSummary.value = script
            repository.recordTurn("ASSISTANT", script)
            speakText(script)
        }
    }

    /**
     * Triggers an automatic daily plan greeting readout once per session if enabled in DataStore.
     */
    fun onHomeScreenDisplayed() {
        viewModelScope.launch {
            refreshDailyPlanText()
            val prefs = userPreferencesDataStore.getSnapshot()
            if (!hasAutoReadPlanInSession && prefs.isFirstLaunchComplete && prefs.userName.isNotBlank() && prefs.autoReadPlanOnLaunch) {
                hasAutoReadPlanInSession = true
                val script = repository.buildSpokenDailyPlan()
                _dailyPlanSummary.value = script
                speakText(script)
            }
        }
    }

    fun refreshDailyPlanText() {
        viewModelScope.launch {
            _dailyPlanSummary.value = repository.buildSpokenDailyPlan()
        }
    }

    /**
     * Builds and speaks the user's daily plan aloud using Android TextToSpeech,
     * greeting them by their DataStore userName.
     */
    fun readDailyPlanAloud() {
        viewModelScope.launch {
            val script = repository.buildSpokenDailyPlan()
            _dailyPlanSummary.value = script
            repository.recordTurn("ASSISTANT", script)
            speakText(script)
        }
    }

    fun speakText(text: String) {
        if (text.isBlank()) return
        val speed = prefsState.value.voiceSpeed.coerceIn(0.6f, 1.8f)
        if (!isTtsReady) {
            pendingSpeechText = text
            _isSpeaking.value = true
            return
        }
        val engine = tts ?: return
        engine.setSpeechRate(speed)
        _isSpeaking.value = true
        val res = engine.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "xestlab_utt_${System.currentTimeMillis()}"
        )
        if (res == TextToSpeech.ERROR) {
            _isSpeaking.value = false
        }
    }

    fun stopSpeaking() {
        pendingSpeechText = null
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        _isSpeaking.value = false
    }

    fun handleUniversalInput(rawInput: String, isSpoken: Boolean = false) {
        val cleaned = rawInput.trim()
        if (cleaned.isEmpty()) return

        viewModelScope.launch {
            repository.recordTurn("USER", cleaned)
            val prefs = userPreferencesDataStore.getSnapshot()
            val name = prefs.userName.ifBlank { "Friend" }
            val intent = AssistantParser.parse(cleaned)

            when (intent) {
                is ParsedIntent.ReadPlan -> {
                    val script = repository.buildSpokenDailyPlan()
                    _dailyPlanSummary.value = script
                    repository.recordTurn("ASSISTANT", script)
                    speakText(script)
                }

                is ParsedIntent.RememberFact -> {
                    repository.saveFact(intent.factText, intent.category)
                    val reply = "Noted, $name. I will remember that ${intent.factText}."
                    respond(reply, speak = true)
                }

                is ParsedIntent.Note -> {
                    repository.saveItem(
                        Item(
                            type = "NOTE",
                            title = intent.title,
                            details = intent.details,
                            sourceText = intent.sourceText
                        )
                    )
                    val reply = "Saved to your notes: ${intent.title}."
                    respond(reply, speak = isSpoken)
                }

                is ParsedIntent.Done -> {
                    val queryLower = intent.targetQuery.lowercase(Locale.getDefault())
                    val matchingChecklist = db.checklistDao().getAllItemsDirect().firstOrNull {
                        it.title.lowercase(Locale.getDefault()).contains(queryLower) ||
                            it.id.lowercase(Locale.getDefault()).contains(queryLower)
                    }
                    if (matchingChecklist != null) {
                        repository.markItemDone(
                            date = todayDateString,
                            itemId = matchingChecklist.id,
                            countValue = matchingChecklist.defaultTargetCount
                        )
                        refreshDailyPlanText()
                        refreshWeeklySummary()
                        respond("Marked daily checklist item done: ${matchingChecklist.title}.", speak = true)
                    } else {
                        val matchingItem = db.itemDao().getItemByTitle(intent.targetQuery)
                        if (matchingItem != null) {
                            repository.updateItemStatus(matchingItem.id, "COMPLETED")
                            refreshDailyPlanText()
                            respond("Completed task: ${matchingItem.title}.", speak = true)
                        } else {
                            respond("Could not find an open item matching '${intent.targetQuery}'.", speak = isSpoken)
                        }
                    }
                }

                is ParsedIntent.PersonReminder -> {
                    val newId = repository.saveItem(
                        Item(
                            type = "REMINDER",
                            title = intent.actionTitle,
                            dueAt = intent.dueAt,
                            priority = "HIGH",
                            contactName = intent.contactName,
                            sourceText = intent.sourceText
                        )
                    )
                    val savedItem = db.itemDao().getItemById(newId)
                    if (savedItem != null) {
                        AlarmScheduler.scheduleItemReminder(appContext, savedItem)
                    }
                    refreshDailyPlanText()
                    val formatted = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(intent.dueAt))
                    respond("Person reminder set for $formatted: ${intent.actionTitle}.", speak = true)
                }

                is ParsedIntent.Reminder -> {
                    val newId = repository.saveItem(
                        Item(
                            type = "REMINDER",
                            title = intent.title,
                            dueAt = intent.dueAt,
                            priority = "NORMAL",
                            sourceText = intent.sourceText
                        )
                    )
                    val savedItem = db.itemDao().getItemById(newId)
                    if (savedItem != null) {
                        AlarmScheduler.scheduleItemReminder(appContext, savedItem)
                    }
                    refreshDailyPlanText()
                    val formatted = SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(intent.dueAt))
                    respond("Reminder scheduled for $formatted: ${intent.title}.", speak = true)
                }

                is ParsedIntent.Goal -> {
                    val item = Item(
                        type = "GOAL",
                        title = intent.title,
                        targetAmount = intent.targetAmount,
                        unit = intent.unit,
                        dueAt = intent.dueAt,
                        priority = "HIGH",
                        sourceText = intent.sourceText
                    )
                    repository.saveItem(item)
                    refreshDailyPlanText()
                    val daily = item.computeDailyRequired() ?: intent.targetAmount
                    val paceStr = String.format(Locale.US, "%.0f", daily)
                    respond(
                        "Goal added: ${intent.title}. Daily pace required is $paceStr ${intent.unit} per day.",
                        speak = true
                    )
                }

                is ParsedIntent.Task -> {
                    val candidate = Item(
                        type = "TASK",
                        title = intent.title,
                        dueAt = intent.dueAt,
                        durationMinutes = intent.durationMinutes,
                        priority = intent.priority,
                        sourceText = intent.sourceText
                    )
                    val proposal = PlannerEngine.propose(db, prefs, candidate)
                    _activeProposal.value = proposal
                    val recLabel = proposal.recommendation.name.replace('_', ' ')
                    respond(
                        "Proposal ($recLabel) for '${candidate.title}': ${proposal.reason} Tap Accept or Drop.",
                        speak = true
                    )
                }

                is ParsedIntent.Question -> {
                    val aiReply = AiClient.generateAssistantResponse(
                        userPrompt = intent.query,
                        db = db,
                        prefs = prefs,
                        securePrefs = securePrefs
                    )
                    if (aiReply != null) {
                        respond(aiReply.replyText, speak = true)
                    } else {
                        val offlineAnswer = repository.buildSpokenDailyPlan()
                        _dailyPlanSummary.value = offlineAnswer
                        respond(offlineAnswer, speak = true)
                    }
                }

                is ParsedIntent.FallbackNote -> {
                    repository.saveItem(
                        Item(
                            type = "NOTE",
                            title = intent.text.take(60),
                            details = intent.text,
                            sourceText = intent.text
                        )
                    )
                    respond("Captured in your notes: ${intent.text.take(60)}.", speak = isSpoken)
                }
            }
        }
    }

    private suspend fun respond(text: String, speak: Boolean) {
        repository.recordTurn("ASSISTANT", text)
        if (speak) {
            speakText(text)
        }
    }

    fun acceptProposal(proposal: PlannerProposal) {
        viewModelScope.launch {
            val id = repository.saveItem(proposal.candidateItem)
            val saved = db.itemDao().getItemById(id)
            if (saved != null && saved.dueAt != null) {
                AlarmScheduler.scheduleItemReminder(appContext, saved)
            }
            _activeProposal.value = null
            refreshDailyPlanText()
            respond("Scheduled '${proposal.candidateItem.title}'.", speak = false)
        }
    }

    fun rejectProposal(proposal: PlannerProposal) {
        viewModelScope.launch {
            repository.saveItem(proposal.candidateItem.copy(status = "DROPPED"))
            _activeProposal.value = null
            respond("Dropped '${proposal.candidateItem.title}'.", speak = false)
        }
    }

    fun markTaskComplete(item: Item) {
        viewModelScope.launch {
            repository.updateItemStatus(item.id, "COMPLETED")
            refreshDailyPlanText()
        }
    }

    fun deleteItem(item: Item) {
        viewModelScope.launch {
            repository.deleteItem(item)
            refreshDailyPlanText()
        }
    }

    fun attachReminderToNote(note: Item, delayMinutes: Int = 60) {
        viewModelScope.launch {
            val trigger = System.currentTimeMillis() + delayMinutes * 60_000L
            val newId = repository.saveItem(
                Item(
                    type = "REMINDER",
                    title = note.title,
                    details = note.details,
                    dueAt = trigger,
                    sourceText = note.sourceText
                )
            )
            val saved = db.itemDao().getItemById(newId)
            if (saved != null) {
                AlarmScheduler.scheduleItemReminder(appContext, saved)
            }
            _statusBanner.value = "Reminder set in $delayMinutes minutes for '${note.title}'."
        }
    }

    fun setNoteSearchQuery(query: String) {
        _noteSearchQuery.value = query
    }

    fun toggleChecklistDone(item: ChecklistItem, currentlyDone: Boolean, countValue: Int = 0) {
        viewModelScope.launch {
            if (!currentlyDone) {
                val count = if (item.isCountType && countValue <= 0) item.defaultTargetCount else countValue
                repository.markItemDone(todayDateString, item.id, count)
            } else {
                repository.markItemNotDone(todayDateString, item.id, "Unchecked")
            }
            refreshDailyPlanText()
            refreshWeeklySummary()
        }
    }

    fun logChecklistReasonNotDone(item: ChecklistItem, reason: String) {
        viewModelScope.launch {
            repository.markItemNotDone(todayDateString, item.id, reason.trim())
            refreshDailyPlanText()
            refreshWeeklySummary()
        }
    }

    fun refreshWeeklySummary() {
        viewModelScope.launch {
            val cal = Calendar.getInstance()
            val endStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
            cal.add(Calendar.DAY_OF_YEAR, -6)
            val startStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
            _weeklySummary.value = repository.getWeeklySummary(startStr, endStr)
        }
    }

    fun buildWeeklyReviewPlainText(): String {
        val w = _weeklySummary.value
        val g = goals.value
        val name = prefsState.value.userName.ifBlank { "Studio" }
        return buildString {
            appendLine("$name - 7-Day Execution Review ($todayDateString)")
            appendLine("Yearly Target: NGN ${String.format(Locale.US, "%,d", g.yearlyGoal)}")
            appendLine("- Outreach Messages: ${w.messagesCount} / ${w.messagesTarget}")
            appendLine("- Active Conversations: ${w.conversationsCount} / ${w.conversationsTarget}")
            appendLine("- Quotes Sent: ${w.quotesCount} / ${w.quotesTarget}")
            appendLine("- Content Published: ${w.contentCount} days")
            appendLine("- Study Blocks: ${w.studyCount} days")
        }
    }

    fun updateProfilePreferences(
        userName: String,
        wakeUpTime: String,
        quietStart: String,
        quietEnd: String,
        quietEnabled: Boolean,
        voiceSpeed: Float,
        autoReadOnLaunch: Boolean
    ) {
        viewModelScope.launch {
            userPreferencesDataStore.updateProfileAndHours(
                userName = userName,
                wakeUpTime = wakeUpTime,
                quietHoursStart = quietStart,
                quietHoursEnd = quietEnd,
                quietHoursEnabled = quietEnabled,
                voiceSpeed = voiceSpeed,
                autoReadPlanOnLaunch = autoReadOnLaunch
            )
            AlarmScheduler.scheduleWakeUpAlarm(appContext, wakeUpTime)
            refreshDailyPlanText()
            _statusBanner.value = "Saved profile & preferences to DataStore."
        }
    }

    fun updateGoals(goal: Goal) {
        viewModelScope.launch {
            repository.saveGoals(goal)
            refreshWeeklySummary()
            _statusBanner.value = "Updated yearly & quarterly goal targets."
        }
    }

    fun toggleReminderSchedule(schedule: ReminderSchedule, active: Boolean) {
        viewModelScope.launch {
            val updated = schedule.copy(isActive = active)
            repository.saveReminder(updated)
            if (active) {
                AlarmScheduler.scheduleDailyBriefing(appContext, updated)
            } else {
                AlarmScheduler.cancelDailyBriefing(appContext, updated.id)
            }
        }
    }

    fun addCustomReminderSchedule(title: String, timeHHmm: String) {
        if (title.isBlank() || timeHHmm.isBlank()) return
        viewModelScope.launch {
            val schedule = ReminderSchedule(
                id = "custom_${System.currentTimeMillis()}",
                title = title.trim(),
                timeString = timeHHmm.trim(),
                isRepeating = true,
                daysOfWeek = "ALL",
                voiceEnabled = true,
                isActive = true,
                isBuiltIn = false
            )
            repository.saveReminder(schedule)
            AlarmScheduler.scheduleDailyBriefing(appContext, schedule)
            _statusBanner.value = "Scheduled daily briefing '${schedule.title}' at ${schedule.timeString}."
        }
    }

    fun deleteReminderSchedule(schedule: ReminderSchedule) {
        viewModelScope.launch {
            AlarmScheduler.cancelDailyBriefing(appContext, schedule.id)
            repository.deleteReminder(schedule)
        }
    }

    fun addFact(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            repository.saveFact(text.trim(), "MANUAL")
        }
    }

    fun deleteFact(fact: Fact) {
        viewModelScope.launch {
            repository.deleteFact(fact)
        }
    }

    fun clearAllFacts() {
        viewModelScope.launch {
            repository.clearAllFacts()
        }
    }

    fun scheduleOneMinuteTestAlarm() {
        AlarmScheduler.scheduleOneMinuteTest(appContext)
        _statusBanner.value = "Test alarm scheduled. Lock screen or wait 60 seconds for voice briefing."
    }

    fun saveImapConfig(
        enabled: Boolean,
        host: String,
        port: Int,
        user: String,
        password: String,
        wifiOnly: Boolean
    ) {
        securePrefs.isImapEnabled = enabled
        securePrefs.imapHost = host
        securePrefs.imapPort = port
        securePrefs.imapUser = user
        if (password.isNotBlank()) {
            securePrefs.imapPassword = password
        }
        securePrefs.isImapWifiOnly = wifiOnly

        if (enabled && user.isNotBlank()) {
            EmailHelper.schedulePeriodicChecks(appContext, wifiOnly)
            _statusBanner.value = "IMAP background checks scheduled every 30 minutes."
        } else {
            EmailHelper.cancelPeriodicChecks(appContext)
            _statusBanner.value = "IMAP background checks disabled."
        }
    }

    fun testImapConnection(host: String, port: Int, user: String, password: String) {
        viewModelScope.launch {
            val effectivePass = password.ifBlank { securePrefs.imapPassword }
            val result = EmailHelper.testImapConnection(host, port, user, effectivePass)
            _statusBanner.value = result.fold(
                onSuccess = { it },
                onFailure = { "IMAP error: ${it.message ?: "Connection failed"}" }
            )
        }
    }

    fun saveGeminiConfig(enabled: Boolean, apiKey: String, model: String) {
        securePrefs.isAiEnabled = enabled
        if (apiKey.isNotBlank()) {
            securePrefs.geminiApiKey = apiKey
        }
        securePrefs.geminiModel = model
        _statusBanner.value = if (enabled) "Optional Gemini reasoning enabled." else "Offline parser active."
    }

    fun testGeminiConnection(apiKey: String, model: String) {
        viewModelScope.launch {
            val keyToUse = apiKey.ifBlank { securePrefs.geminiApiKey }
            val res = AiClient.testConnection(keyToUse, model)
            _statusBanner.value = res.fold(
                onSuccess = { "Gemini connected: $it" },
                onFailure = { "Gemini error: ${it.message}" }
            )
        }
    }

    fun exportBackupJson(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val json = repository.exportJson()
            onReady(json)
        }
    }

    fun importBackupJson(jsonString: String) {
        viewModelScope.launch {
            val ok = repository.importJson(jsonString)
            refreshDailyPlanText()
            refreshWeeklySummary()
            _statusBanner.value = if (ok) {
                "Imported backup and web tracker data successfully."
            } else {
                "Import failed: invalid JSON format."
            }
        }
    }
}
