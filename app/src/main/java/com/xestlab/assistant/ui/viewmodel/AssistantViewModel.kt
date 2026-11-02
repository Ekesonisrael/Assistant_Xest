package com.xestlab.assistant.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xestlab.assistant.ai.AiClient
import com.xestlab.assistant.ai.AssistantParser
import com.xestlab.assistant.ai.ParsedIntent
import com.xestlab.assistant.ai.PlannerEngine
import com.xestlab.assistant.ai.PlannerProposal
import com.xestlab.assistant.data.LlmProvider
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
import com.xestlab.assistant.data.model.UnreadEmailEntity
import com.xestlab.assistant.data.model.UserSettings
import com.xestlab.assistant.data.repository.AssistantRepository
import com.xestlab.assistant.data.repository.WeeklySummaryData
import com.xestlab.assistant.service.AlarmScheduler
import com.xestlab.assistant.service.EmailHelper
import com.xestlab.assistant.service.InstalledTtsVoiceInfo
import com.xestlab.assistant.service.TtsGreetingHelper
import com.xestlab.assistant.service.VoiceEngine
import com.xestlab.assistant.service.WakeWordDiagnostics
import com.xestlab.assistant.service.WakeWordService
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

class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    val userPreferencesDataStore = UserPreferencesDataStore(appContext)
    private val db = AppDatabase.getInstance(appContext)
    val repository = AssistantRepository(db, userPreferencesDataStore)
    val securePrefs = SecurePreferences(appContext)

    private var hasAutoReadPlanInSession = false

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    private val voiceEngine = VoiceEngine(
        context = appContext,
        securePrefs = securePrefs,
        onSpeakingStateChanged = { speaking -> _isSpeaking.value = speaking }
    )

    // Null only while initial DataStore read is in flight so MainActivity routes without flicker
    val userPrefsLoaded: StateFlow<UserPreferencesState?> = userPreferencesDataStore.preferencesFlow
        .map<UserPreferencesState, UserPreferencesState?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val prefsState: StateFlow<UserPreferencesState> = userPreferencesDataStore.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferencesState())

    private val _dailyPlanSummary = MutableStateFlow("")
    val dailyPlanSummary: StateFlow<String> = _dailyPlanSummary.asStateFlow()

    private val _activeProposal = MutableStateFlow<PlannerProposal?>(null)
    val activeProposal: StateFlow<PlannerProposal?> = _activeProposal.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _noteSearchQuery = MutableStateFlow("")
    val noteSearchQuery: StateFlow<String> = _noteSearchQuery.asStateFlow()

    private val _activeProvider = MutableStateFlow(LlmProvider.GROQ)
    val activeProvider: StateFlow<LlmProvider> = _activeProvider.asStateFlow()

    private val _aiEnabledState = MutableStateFlow(securePrefs.isAiEnabled)
    val aiEnabledState: StateFlow<Boolean> = _aiEnabledState.asStateFlow()

    private val _savedGeminiSummary = MutableStateFlow(securePrefs.maskedKeyPreview(LlmProvider.GEMINI))
    val savedGeminiSummary: StateFlow<String> = _savedGeminiSummary.asStateFlow()

    private val _savedGroqSummary = MutableStateFlow(securePrefs.maskedKeyPreview(LlmProvider.GROQ))
    val savedGroqSummary: StateFlow<String> = _savedGroqSummary.asStateFlow()

    private val _llmTestDiagnosticText = MutableStateFlow(
        "Primary Engine: GROQ (${AiClient.GROQ_MODEL}) | Groq vault: ${securePrefs.maskedKeyPreview(LlmProvider.GROQ)}"
    )
    val llmTestDiagnosticText: StateFlow<String> = _llmTestDiagnosticText.asStateFlow()

    private val _llmTestIsError = MutableStateFlow(false)
    val llmTestIsError: StateFlow<Boolean> = _llmTestIsError.asStateFlow()

    // openWakeWord ONNX ("Hey Jarvis") Reactive State & Live Diagnostics
    private val _wakeWordEnabledState = MutableStateFlow(securePrefs.isWakeWordEnabled)
    val wakeWordEnabledState: StateFlow<Boolean> = _wakeWordEnabledState.asStateFlow()

    private val _wakeWordThreshold = MutableStateFlow(securePrefs.wakeWordThreshold)
    val wakeWordThreshold: StateFlow<Float> = _wakeWordThreshold.asStateFlow()

    val wakeWordDiagnostics: StateFlow<WakeWordDiagnostics> = WakeWordService.diagnosticsFlow
    val wakeWordStatusText: StateFlow<String> = WakeWordService.statusMessageFlow
    val wakeWordStatusIsError: StateFlow<Boolean> = WakeWordService.statusIsErrorFlow

    // ElevenLabs Cloud Voice & Android TTS Fallback Picker State
    private val _savedElevenLabsSummary = MutableStateFlow(securePrefs.maskedElevenLabsKeyPreview())
    val savedElevenLabsSummary: StateFlow<String> = _savedElevenLabsSummary.asStateFlow()

    val voiceStatusText: StateFlow<String> = VoiceEngine.statusMessageFlow
    val voiceStatusIsError: StateFlow<Boolean> = VoiceEngine.statusIsErrorFlow
    val installedTtsVoices: StateFlow<List<InstalledTtsVoiceInfo>> = VoiceEngine.installedVoicesFlow
    val monthlyElevenLabsChars: StateFlow<Int> = VoiceEngine.monthlyCharsUsedFlow
    val elevenLabsPausedUntil: StateFlow<Long> = VoiceEngine.cloudPausedUntilFlow
    val elevenLabsPauseMessage: StateFlow<String> = VoiceEngine.cloudPauseMessageFlow

    private val _selectedAndroidVoiceName = MutableStateFlow(securePrefs.androidTtsVoiceName)
    val selectedAndroidVoiceName: StateFlow<String> = _selectedAndroidVoiceName.asStateFlow()

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

    val unreadEmails: StateFlow<List<UnreadEmailEntity>> = repository.unreadEmails
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val filteredNotes: StateFlow<List<Item>> = _noteSearchQuery
        .flatMapLatest { query -> repository.searchNotes(query) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        securePrefs.llmProvider = LlmProvider.GROQ
        _activeProvider.value = LlmProvider.GROQ
        VoiceEngine.syncPersistedCloudState(securePrefs)
        WakeWordService.syncPersistedDiagnostics(appContext)
        viewModelScope.launch {
            AppDatabase.ensureSeeded(db)
            refreshDailyPlanText()
            refreshWeeklySummary()
        }
    }

    override fun onCleared() {
        voiceEngine.shutdown()
        super.onCleared()
    }

    fun clearStatusBanner() {
        _statusBanner.value = null
    }

    fun setStatusBanner(message: String) {
        _statusBanner.value = message
    }

    fun completeFirstLaunch(userName: String, wakeUpTime: String) {
        val trimmedName = userName.trim()
        if (trimmedName.isBlank()) return
        val cleanWake = wakeUpTime.trim().ifBlank { "07:30" }

        viewModelScope.launch {
            userPreferencesDataStore.completeFirstLaunch(trimmedName, cleanWake)
            db.settingsDao().saveSettings(
                UserSettings(
                    id = 1,
                    userName = trimmedName,
                    wakeUpTime = cleanWake,
                    voiceSpeed = 1.0f,
                    selectedVoiceName = securePrefs.androidTtsVoiceName,
                    isFirstRunComplete = true
                )
            )
            val schedules = db.reminderDao().getAllRemindersDirect()
            val pending = db.itemDao().getPendingItemsDirect()
            AlarmScheduler.scheduleAllActive(appContext, cleanWake, schedules, pending)

            hasAutoReadPlanInSession = true
            val script = repository.buildSpokenDailyPlan(isWakeUpAlarm = true)
            _dailyPlanSummary.value = script
            repository.recordTurn("ASSISTANT", script)
            speakText(script, isWakeUpBriefing = true)
        }
    }

    fun onHomeScreenDisplayed() {
        viewModelScope.launch {
            refreshDailyPlanText()
            val prefs = userPreferencesDataStore.getSnapshot()
            if (!hasAutoReadPlanInSession && prefs.isFirstLaunchComplete && prefs.userName.isNotBlank() && prefs.autoReadPlanOnLaunch) {
                hasAutoReadPlanInSession = true
                val script = repository.buildSpokenDailyPlan()
                _dailyPlanSummary.value = script
                speakText(script, isWakeUpBriefing = true)
            }
        }
    }

    fun refreshDailyPlanText() {
        viewModelScope.launch {
            _dailyPlanSummary.value = repository.buildSpokenDailyPlan()
        }
    }

    fun readDailyPlanAloud() {
        viewModelScope.launch {
            val script = repository.buildSpokenDailyPlan(isWakeUpAlarm = true)
            _dailyPlanSummary.value = script
            repository.recordTurn("ASSISTANT", script)
            speakText(script, isWakeUpBriefing = true)
        }
    }

    fun speakText(text: String, isWakeUpBriefing: Boolean = false) {
        if (text.isBlank()) return
        val rate = prefsState.value.voiceSpeed.coerceIn(0.6f, 1.8f)
        voiceEngine.speak(
            text = text,
            fallbackSpeechRate = rate,
            isWakeUpBriefing = isWakeUpBriefing
        )
    }

    fun stopSpeaking() {
        voiceEngine.stop()
    }

    fun clearConversationHistory() {
        viewModelScope.launch {
            repository.clearConversation()
        }
    }

    /**
     * Universal voice & text input handler:
     * - ONLY saves Notes when user explicitly says "Note: [text]".
     * - Answers "What did I get in my email today?" from the Room unread email cache.
     * - Routes chat/questions to Gemini or Groq when AI toggle is ON and a key is saved,
     *   speaking the exact error fallback if the API fails.
     */
    fun handleUniversalInput(rawInput: String, isSpoken: Boolean = false) {
        val cleaned = rawInput.trim()
        if (cleaned.isEmpty()) return

        viewModelScope.launch {
            repository.recordTurn("USER", cleaned)
            val prefs = userPreferencesDataStore.getSnapshot()
            val name = prefs.userName.ifBlank { "King Israel" }
            val intent = AssistantParser.parse(cleaned)

            when (intent) {
                is ParsedIntent.ExplicitNote -> {
                    repository.saveItem(
                        Item(
                            type = "NOTE",
                            title = intent.title,
                            details = intent.details,
                            sourceText = intent.sourceText
                        )
                    )
                    respond("Note saved: ${intent.title}.", speak = true)
                }

                is ParsedIntent.CheckEmails -> {
                    if (securePrefs.isImapEnabled &&
                        securePrefs.imapUser.isNotBlank() &&
                        securePrefs.imapPassword.isNotBlank() &&
                        db.emailDao().getRecentUnreadDirect(1).isEmpty()
                    ) {
                        _isThinking.value = true
                        EmailHelper.syncUnreadEmailsAndNotify(appContext, postNotifications = false)
                        _isThinking.value = false
                    }
                    if (securePrefs.hasGroqKey()) {
                        routeChatToBrainOrOffline(cleaned, prefs)
                    } else {
                        val emailSummary = repository.buildUnreadEmailSpokenSummary()
                        respond(emailSummary, speak = true)
                    }
                }

                is ParsedIntent.ReadPlan -> {
                    val script = repository.buildSpokenDailyPlan(isWakeUpAlarm = true)
                    _dailyPlanSummary.value = script
                    respond(script, speak = true, isWakeUpBriefing = true)
                }

                is ParsedIntent.RememberFact -> {
                    repository.saveFact(intent.factText, intent.category)
                    respond("Locked into memory, $name: ${intent.factText}.", speak = true)
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
                        val allCleared = TtsGreetingHelper.countRemainingTasksForToday(db) == 0
                        respond(TtsGreetingHelper.buildTaskCompletionSpeech(allCleared), speak = true)
                    } else {
                        val matchingItem = db.itemDao().getItemByTitle(intent.targetQuery)
                        if (matchingItem != null) {
                            repository.updateItemStatus(matchingItem.id, "COMPLETED")
                            refreshDailyPlanText()
                            val allCleared = TtsGreetingHelper.countRemainingTasksForToday(db) == 0
                            respond(TtsGreetingHelper.buildTaskCompletionSpeech(allCleared), speak = true)
                        } else {
                            routeChatToBrainOrOffline(cleaned, prefs)
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
                    val formatted = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(intent.dueAt))
                    respond("Reminder locked for $formatted: ${intent.actionTitle}.", speak = true)
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
                    val formatted = SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(intent.dueAt))
                    respond("Reminder set for $formatted: ${intent.title}.", speak = true)
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
                        "Goal added: ${intent.title}. Daily target pace is $paceStr ${intent.unit} per day.",
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
                        "Planner suggests $recLabel for '${candidate.title}'. ${proposal.reason}",
                        speak = true
                    )
                }

                is ParsedIntent.ChatOrQuestion -> {
                    if (securePrefs.isImapEnabled &&
                        securePrefs.imapUser.isNotBlank() &&
                        securePrefs.imapPassword.isNotBlank() &&
                        db.emailDao().getRecentUnreadDirect(1).isEmpty() &&
                        (intent.query.contains("meeting", ignoreCase = true) ||
                            intent.query.contains("invite", ignoreCase = true) ||
                            intent.query.contains("email", ignoreCase = true))
                    ) {
                        _isThinking.value = true
                        EmailHelper.syncUnreadEmailsAndNotify(appContext, postNotifications = false)
                        _isThinking.value = false
                    }
                    routeChatToBrainOrOffline(intent.query, prefs)
                }
            }
        }
    }

    private suspend fun routeChatToBrainOrOffline(
        userMessage: String,
        prefs: UserPreferencesState
    ) {
        if (securePrefs.isAiEnabled || securePrefs.hasGroqKey()) {
            val storedGroqKey = securePrefs.getKeyForProvider(LlmProvider.GROQ)
            if (storedGroqKey.isNullOrBlank()) {
                val missingMsg = "No GROQ API key found in EncryptedSharedPreferences. Open Settings and save your Groq key first."
                _llmTestIsError.value = true
                _llmTestDiagnosticText.value = missingMsg
                repository.recordTurn("ASSISTANT", "${AiClient.AI_BRAIN_UNREACHABLE_MESSAGE}\n[$missingMsg]")
                speakText(AiClient.AI_BRAIN_UNREACHABLE_MESSAGE)
                return
            }
            _isThinking.value = true
            val result = AiClient.generateAssistantResponse(
                userPrompt = userMessage,
                db = db,
                prefs = prefs,
                securePrefs = securePrefs
            )
            _isThinking.value = false

            result.fold(
                onSuccess = { aiResp ->
                    _llmTestIsError.value = false
                    _llmTestDiagnosticText.value =
                        "HTTP 200 OK [${aiResp.providerUsed.name}/${aiResp.modelUsed}]: ${aiResp.replyText}"
                    // Speak Groq's response out loud via TextToSpeech
                    respond(aiResp.replyText, speak = true)
                },
                onFailure = { err ->
                    val exactDiag = AiClient.formatThrowableDiagnostic(err)
                    _llmTestIsError.value = true
                    _llmTestDiagnosticText.value = exactDiag
                    repository.recordTurn(
                        "ASSISTANT",
                        "${AiClient.AI_BRAIN_UNREACHABLE_MESSAGE}\n[Diagnostic: $exactDiag]"
                    )
                    speakText(AiClient.AI_BRAIN_UNREACHABLE_MESSAGE)
                }
            )
        } else {
            val offlinePlan = repository.buildSpokenDailyPlan()
            _dailyPlanSummary.value = offlinePlan
            respond(
                "$offlinePlan (Enable Groq AI routing in Settings for full business coach conversation, or prefix with 'Note:' to save a note.)",
                speak = true
            )
        }
    }

    private suspend fun respond(
        text: String,
        speak: Boolean,
        isWakeUpBriefing: Boolean = false
    ) {
        repository.recordTurn("ASSISTANT", text)
        if (speak) {
            speakText(text, isWakeUpBriefing = isWakeUpBriefing)
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

    fun promoteEmailToTask(email: UnreadEmailEntity) {
        viewModelScope.launch {
            repository.promoteEmailToTask(email)
            refreshDailyPlanText()
            val name = prefsState.value.userName.ifBlank { "King Israel" }
            _statusBanner.value = "Added email to your Tasks."
            speakText("Added '${email.extractedTaskTitle ?: email.subject}' to your tasks, $name.")
        }
    }

    /**
     * Marks a task complete and speaks a varied, non-repeating supportive message.
     */
    fun markTaskComplete(item: Item) {
        viewModelScope.launch {
            repository.updateItemStatus(item.id, "COMPLETED")
            refreshDailyPlanText()
            val allCleared = TtsGreetingHelper.countRemainingTasksForToday(db) == 0
            speakText(TtsGreetingHelper.buildTaskCompletionSpeech(allCleared))
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

    /**
     * Toggles a daily checklist item and speaks a varied, non-repeating supportive phrase on completion.
     */
    fun toggleChecklistDone(item: ChecklistItem, currentlyDone: Boolean, countValue: Int = 0) {
        viewModelScope.launch {
            if (!currentlyDone) {
                val count = if (item.isCountType && countValue <= 0) item.defaultTargetCount else countValue
                repository.markItemDone(todayDateString, item.id, count)

                val allCleared = TtsGreetingHelper.countRemainingTasksForToday(db) == 0
                speakText(TtsGreetingHelper.buildTaskCompletionSpeech(allCleared))
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
        val name = prefsState.value.userName.ifBlank { "King Israel" }
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
            val schedules = db.reminderDao().getAllRemindersDirect()
            val pending = db.itemDao().getPendingItemsDirect()
            AlarmScheduler.scheduleAllActive(appContext, wakeUpTime, schedules, pending)
            refreshDailyPlanText()
            _statusBanner.value = "Saved profile & 7:30 AM wake-up schedule."
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
        _statusBanner.value = "Test wake-up alarm set for 60 seconds from now."
    }

    // --- Dual LLM & ElevenLabs Encrypted Settings ---

    fun setAiRoutingEnabled(enabled: Boolean) {
        securePrefs.isAiEnabled = enabled
        _aiEnabledState.value = enabled
    }

    fun setLlmProvider(provider: LlmProvider) {
        securePrefs.llmProvider = provider
        _activeProvider.value = provider
    }

    fun refreshSavedKeySummaries() {
        _savedGeminiSummary.value = securePrefs.maskedKeyPreview(LlmProvider.GEMINI)
        _savedGroqSummary.value = securePrefs.maskedKeyPreview(LlmProvider.GROQ)
        _savedElevenLabsSummary.value = securePrefs.maskedElevenLabsKeyPreview()
        _wakeWordEnabledState.value = securePrefs.isWakeWordEnabled
        _wakeWordThreshold.value = securePrefs.wakeWordThreshold
        _selectedAndroidVoiceName.value = securePrefs.androidTtsVoiceName
        VoiceEngine.syncPersistedCloudState(securePrefs)
        voiceEngine.refreshInstalledVoices()
    }

    fun setDiagnosticError(message: String) {
        _llmTestIsError.value = true
        _llmTestDiagnosticText.value = message
    }

    fun saveLlmSettings(
        aiEnabled: Boolean,
        provider: LlmProvider = LlmProvider.GROQ,
        geminiKeyInput: String = "",
        groqKeyInput: String
    ) {
        /*
         * Gemini key saving temporarily disabled while Groq is primary:
         * if (geminiKeyInput.isNotBlank()) {
         *     securePrefs.geminiApiKey = geminiKeyInput
         * }
         */
        if (groqKeyInput.isNotBlank()) {
            securePrefs.groqApiKey = groqKeyInput
        }
        securePrefs.llmProvider = LlmProvider.GROQ
        _activeProvider.value = LlmProvider.GROQ

        val hasGroqKey = securePrefs.hasGroqKey()
        val autoEnable = if (groqKeyInput.isNotBlank()) true else aiEnabled
        securePrefs.isAiEnabled = autoEnable
        _aiEnabledState.value = autoEnable
        refreshSavedKeySummaries()

        val activeVaultPreview = securePrefs.maskedKeyPreview(LlmProvider.GROQ)
        _llmTestIsError.value = !hasGroqKey
        _llmTestDiagnosticText.value = if (hasGroqKey) {
            "Saved GROQ key in EncryptedSharedPreferences ($activeVaultPreview). Tap 'Test GROQ' to verify ${AiClient.GROQ_MODEL}."
        } else {
            "Settings saved, but no GROQ API key is stored yet."
        }
    }

    /**
     * Resolves the Groq API key from the input field if typed; if empty, fetches the saved key
     * directly from EncryptedSharedPreferences (SecurePreferences).
     */
    fun resolveApiKeyForTest(
        provider: LlmProvider = LlmProvider.GROQ,
        geminiKeyDraft: String = "",
        groqKeyDraft: String
    ): String? {
        val draftClean = SecurePreferences.sanitizeApiKey(groqKeyDraft)
        if (draftClean.isNotEmpty()) {
            securePrefs.groqApiKey = draftClean
            refreshSavedKeySummaries()
            return draftClean
        }
        return securePrefs.getKeyForProvider(LlmProvider.GROQ)
    }

    fun testActiveLlmConnection(
        provider: LlmProvider = LlmProvider.GROQ,
        resolvedKey: String
    ) {
        viewModelScope.launch {
            _isThinking.value = true
            _llmTestIsError.value = false
            _llmTestDiagnosticText.value =
                "Testing GROQ (${AiClient.GROQ_MODEL}) over HTTPS (key length=${resolvedKey.length} chars)..."

            // Gemini disabled for now; always test primary Groq engine ("openai/gpt-oss-120b")
            val result = AiClient.testGroqConnection(resolvedKey)
            _isThinking.value = false

            result.fold(
                onSuccess = { diag ->
                    securePrefs.llmProvider = LlmProvider.GROQ
                    securePrefs.isAiEnabled = true
                    _activeProvider.value = LlmProvider.GROQ
                    _aiEnabledState.value = true
                    _llmTestIsError.value = false
                    _llmTestDiagnosticText.value =
                        "HTTP ${diag.statusCode} OK [${diag.provider.name}/${diag.modelUsed}]: ${diag.replyText}"
                    speakText(diag.replyText)
                },
                onFailure = { err ->
                    val exactError = AiClient.formatThrowableDiagnostic(err)
                    _llmTestIsError.value = true
                    _llmTestDiagnosticText.value = exactError
                }
            )
        }
    }

    fun saveElevenLabsKey(apiKeyInput: String) {
        val cleaned = SecurePreferences.sanitizeApiKey(apiKeyInput)
        securePrefs.elevenLabsApiKey = cleaned
        VoiceEngine.syncPersistedCloudState(securePrefs)
        refreshSavedKeySummaries()
        if (cleaned.isEmpty()) {
            _statusBanner.value = "Cleared ElevenLabs key. Using Android TTS (1.0x)."
            VoiceEngine.updateExternalDiagnostic(
                "Cleared ElevenLabs key — using Android TTS fallback (1.0x).",
                isError = false
            )
        } else {
            val preview = securePrefs.maskedElevenLabsKeyPreview()
            _statusBanner.value = "Encrypted & saved ElevenLabs voice key ($preview)."
            VoiceEngine.updateExternalDiagnostic(
                "Saved ElevenLabs key ($preview, Voice ID: ${securePrefs.elevenLabsVoiceId}). Tap 'Test Voice' to verify.",
                isError = false
            )
        }
    }

    fun selectAndroidTtsVoice(voiceName: String) {
        val trimmed = voiceName.trim()
        voiceEngine.selectAndroidVoice(trimmed)
        _selectedAndroidVoiceName.value = trimmed
        viewModelScope.launch {
            userPreferencesDataStore.saveSelectedVoiceName(trimmed)
            val existing = db.settingsDao().getSettingsDirect()
            if (existing != null) {
                db.settingsDao().saveSettings(existing.copy(selectedVoiceName = trimmed))
            }
        }
    }

    fun setFallbackVoiceSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.6f, 1.8f)
        viewModelScope.launch {
            userPreferencesDataStore.saveVoiceSpeed(clamped)
            val existing = db.settingsDao().getSettingsDirect()
            if (existing != null) {
                db.settingsDao().saveSettings(existing.copy(voiceSpeed = clamped))
            }
        }
    }

    fun testVoicePlayback(elevenLabsDraftKey: String) {
        val draftClean = SecurePreferences.sanitizeApiKey(elevenLabsDraftKey)
        if (draftClean.isNotEmpty()) {
            securePrefs.elevenLabsApiKey = draftClean
            VoiceEngine.syncPersistedCloudState(securePrefs)
            refreshSavedKeySummaries()
        }
        val displayName = prefsState.value.userName.ifBlank { "King Israel" }
        val greeting = TtsGreetingHelper.getTimeAwareGreeting(displayName)
        speakText("$greeting Xestlab Assistant voice engine is active and ready.")
    }

    // --- Smart IMAP Email Checker ---

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

        if (enabled && user.isNotBlank() && securePrefs.imapPassword.isNotBlank()) {
            EmailHelper.schedulePeriodicChecks(appContext, wifiOnly)
            _statusBanner.value = "Encrypted IMAP saved. Checking unread emails every 30 minutes."
            syncEmailsNow()
        } else {
            EmailHelper.cancelPeriodicChecks(appContext)
            _statusBanner.value = "IMAP background checks paused."
        }
    }

    fun syncEmailsNow(
        hostDraft: String? = null,
        portDraft: Int? = null,
        userDraft: String? = null,
        passDraft: String? = null
    ) {
        viewModelScope.launch {
            if (!hostDraft.isNullOrBlank()) securePrefs.imapHost = hostDraft
            if (portDraft != null) securePrefs.imapPort = portDraft
            if (!userDraft.isNullOrBlank()) securePrefs.imapUser = userDraft
            if (!passDraft.isNullOrBlank()) securePrefs.imapPassword = passDraft

            val result = EmailHelper.syncUnreadEmailsAndNotify(appContext, postNotifications = true)
            _statusBanner.value = result.fold(
                onSuccess = { list ->
                    val actionableCount = list.count { it.isActionable }
                    "Synced ${list.size} unread emails ($actionableCount actionable tasks/invoices)."
                },
                onFailure = { err ->
                    "IMAP sync failed: ${err.message ?: "Check credentials or network"}"
                }
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
                "Imported JSON pipeline, logs, and goals successfully."
            } else {
                "Import failed: invalid JSON format."
            }
        }
    }

    // --- openWakeWord ONNX ("Hey Jarvis") Controls ---

    fun setWakeWordEnabled(enabled: Boolean) {
        securePrefs.wakeWordName = "Jarvis"
        securePrefs.isWakeWordEnabled = enabled
        _wakeWordEnabledState.value = enabled

        if (enabled) {
            WakeWordService.start(appContext, initiatedByUserTap = true)
            _statusBanner.value = "Listening for 'Hey Jarvis'... (openWakeWord ONNX active)"
        } else {
            WakeWordService.stop(appContext)
            _statusBanner.value = "'Hey Jarvis' background wake word listener stopped."
        }
    }

    fun setWakeWordThreshold(threshold: Float) {
        val clamped = threshold.coerceIn(0.05f, 0.90f)
        securePrefs.wakeWordThreshold = clamped
        _wakeWordThreshold.value = clamped
    }

    fun testJarvisLoopFromSettings() {
        securePrefs.wakeWordName = "Jarvis"
        WakeWordService.triggerJarvisLoopTest(appContext)
    }
}
