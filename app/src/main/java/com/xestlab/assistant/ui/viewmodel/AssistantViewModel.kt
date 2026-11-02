package com.xestlab.assistant.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xestlab.assistant.ai.AiClient
import com.xestlab.assistant.ai.AssistantParser
import com.xestlab.assistant.ai.MoneyAndLeadParser
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
import com.xestlab.assistant.data.model.CustomEmailTagEntity
import com.xestlab.assistant.data.model.DailyLog
import com.xestlab.assistant.data.model.EmailSenderRuleEntity
import com.xestlab.assistant.data.model.Fact
import com.xestlab.assistant.data.model.Goal
import com.xestlab.assistant.data.model.Item
import com.xestlab.assistant.data.model.Lead
import com.xestlab.assistant.data.model.LeadEvent
import com.xestlab.assistant.data.model.MessageTemplateEntity
import com.xestlab.assistant.data.model.Payment
import com.xestlab.assistant.data.model.ReminderSchedule
import com.xestlab.assistant.data.model.UnreadEmailEntity
import com.xestlab.assistant.data.model.UserSettings
import com.xestlab.assistant.data.repository.AssistantRepository
import com.xestlab.assistant.data.repository.WeeklySummaryData
import com.xestlab.assistant.service.AlarmScheduler
import com.xestlab.assistant.service.DueFollowUpUiModel
import com.xestlab.assistant.service.EmailHelper
import com.xestlab.assistant.service.InstalledTtsVoiceInfo
import com.xestlab.assistant.service.LeadCadenceEngine
import com.xestlab.assistant.service.RevenueGaugeMetrics
import com.xestlab.assistant.service.TtsGreetingHelper
import com.xestlab.assistant.service.VoiceEngine
import com.xestlab.assistant.service.WakeWordDiagnostics
import com.xestlab.assistant.service.WakeWordService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

sealed class PendingConfirmation {
    abstract val promptText: String

    data class AddLead(
        val name: String,
        val service: String,
        val phone: String,
        val observation: String,
        override val promptText: String
    ) : PendingConfirmation()

    data class UpdateLeadStage(
        val leadId: Long,
        val leadName: String,
        val targetStage: String,
        val quoteAmount: Long?,
        override val promptText: String
    ) : PendingConfirmation()

    data class LogPayment(
        val clientName: String,
        val matchedLeadId: Long?,
        val matchedLeadName: String?,
        val matchedLeadStage: String?,
        val amount: Long,
        val paymentType: String,
        val note: String,
        val canMarkWon: Boolean,
        override val promptText: String
    ) : PendingConfirmation()
}

class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    val userPreferencesDataStore = UserPreferencesDataStore(appContext)
    private val db = AppDatabase.getInstance(appContext)
    val repository = AssistantRepository(db, userPreferencesDataStore)
    val securePrefs = SecurePreferences(appContext)

    private var hasAutoReadPlanInSession = false
    private var suppressFollowUpQuestions = false

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
    val wakeWordIsRunning: StateFlow<Boolean> = WakeWordService.isRunningFlow
    val wakeWordStatusText: StateFlow<String> = WakeWordService.statusMessageFlow
    val wakeWordStatusIsError: StateFlow<Boolean> = WakeWordService.statusIsErrorFlow

    // System Health Telemetry Flows (Alarms & Email)
    val lastEmailCheckTime: StateFlow<Long> = EmailHelper.lastCheckTimeFlow
    val lastEmailCheckResult: StateFlow<String> = EmailHelper.lastCheckResultFlow
    val lastEmailCheckIsError: StateFlow<Boolean> = EmailHelper.lastCheckIsErrorFlow
    val lastAlarmFiredAt: StateFlow<Long> = AlarmScheduler.lastAlarmFiredAtFlow
    val lastAlarmFiredTitle: StateFlow<String> = AlarmScheduler.lastAlarmFiredTitleFlow
    val nextAlarmAt: StateFlow<Long> = AlarmScheduler.nextAlarmAtFlow
    val nextAlarmTitle: StateFlow<String> = AlarmScheduler.nextAlarmTitleFlow

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

    val senderRules: StateFlow<List<EmailSenderRuleEntity>> = repository.senderRules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val customEmailTags: StateFlow<List<CustomEmailTagEntity>> = repository.customEmailTags
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val leads: StateFlow<List<Lead>> = repository.leads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allLeadEvents: StateFlow<List<LeadEvent>> = repository.allLeadEvents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val messageTemplates: StateFlow<List<MessageTemplateEntity>> = repository.messageTemplates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val payments: StateFlow<List<Payment>> = repository.payments
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dueFollowUpsToday: StateFlow<List<DueFollowUpUiModel>> = combine(
        repository.leads,
        repository.pendingItems
    ) { currentLeads, currentPending ->
        LeadCadenceEngine.computeDueFollowUpsToday(currentLeads, currentPending)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val needsAttentionLeads: StateFlow<List<Lead>> = repository.leads
        .map { currentLeads -> LeadCadenceEngine.computeNeedsAttentionLeads(currentLeads) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val revenueGauge: StateFlow<RevenueGaugeMetrics> = combine(
        repository.goals.map { it ?: Goal() },
        repository.payments
    ) { goal, paymentList ->
        LeadCadenceEngine.computeRevenueGauge(goal, paymentList)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        LeadCadenceEngine.computeRevenueGauge(Goal(), emptyList())
    )

    private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingConfirmation?> = _pendingConfirmation.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val filteredNotes: StateFlow<List<Item>> = _noteSearchQuery
        .flatMapLatest { query -> repository.searchNotes(query) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        securePrefs.llmProvider = LlmProvider.GROQ
        _activeProvider.value = LlmProvider.GROQ
        VoiceEngine.syncPersistedCloudState(securePrefs)
        WakeWordService.syncPersistedDiagnostics(appContext)
        EmailHelper.syncPersistedHealth(securePrefs)
        AlarmScheduler.syncAlarmTelemetry(appContext)
        viewModelScope.launch {
            AppDatabase.ensureSeeded(db)
            val snap = userPreferencesDataStore.getSnapshot()
            if (snap.isFirstLaunchComplete && securePrefs.nextAlarmTimestamp <= System.currentTimeMillis()) {
                val schedules = db.reminderDao().getAllRemindersDirect()
                val pending = db.itemDao().getPendingItemsDirect()
                AlarmScheduler.scheduleAllActive(appContext, snap.wakeUpTime, schedules, pending)
            }
            refreshDailyPlanText()
            refreshWeeklySummary()
        }
        viewModelScope.launch {
            combine(
                repository.leads,
                repository.allLeadEvents,
                repository.payments
            ) { _, _, _ -> Unit }.collect {
                refreshWeeklySummary()
            }
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
            securePrefs.markBriefingShownToday()
            val firstGreeting = securePrefs.consumePeriodGreetingIfNeeded(trimmedName)
            val script = repository.buildSpokenDailyPlan(
                overrideGreetingPrefix = firstGreeting.takeIf { it.isNotBlank() },
                isWakeUpAlarm = true,
                includeGreeting = false
            )
            _dailyPlanSummary.value = script
            repository.recordTurn("ASSISTANT", script)
            speakText(script, isWakeUpBriefing = true)
        }
    }

    fun onHomeScreenDisplayed() {
        viewModelScope.launch {
            refreshDailyPlanText()
            val prefs = userPreferencesDataStore.getSnapshot()
            if (
                !hasAutoReadPlanInSession &&
                prefs.isFirstLaunchComplete &&
                prefs.userName.isNotBlank() &&
                prefs.autoReadPlanOnLaunch &&
                securePrefs.shouldShowAutoBriefingToday()
            ) {
                hasAutoReadPlanInSession = true
                securePrefs.markBriefingShownToday()
                val periodGreeting = securePrefs.consumePeriodGreetingIfNeeded(prefs.userName)
                val script = repository.buildSpokenDailyPlan(
                    overrideGreetingPrefix = periodGreeting.takeIf { it.isNotBlank() },
                    includeGreeting = false
                )
                _dailyPlanSummary.value = script
                speakText(script, isWakeUpBriefing = true)
            }
        }
    }

    fun refreshDailyPlanText() {
        viewModelScope.launch {
            _dailyPlanSummary.value = repository.buildSpokenDailyPlan(includeGreeting = false)
        }
    }

    fun readDailyPlanAloud() {
        viewModelScope.launch {
            val prefs = userPreferencesDataStore.getSnapshot()
            val periodGreeting = securePrefs.consumePeriodGreetingIfNeeded(prefs.userName)
            securePrefs.markBriefingShownToday()
            val script = repository.buildSpokenDailyPlan(
                overrideGreetingPrefix = periodGreeting.takeIf { it.isNotBlank() },
                isWakeUpAlarm = true,
                includeGreeting = false
            )
            _dailyPlanSummary.value = script
            repository.recordTurn("ASSISTANT", script)
            speakText(script, isWakeUpBriefing = true)
        }
    }

    private fun applyDeterministicGreeting(
        body: String,
        userName: String,
        allowGreeting: Boolean = true
    ): String {
        val cleanBody = body.trim()
        if (!allowGreeting || cleanBody.isEmpty()) return cleanBody
        val greeting = securePrefs.consumePeriodGreetingIfNeeded(userName)
        return if (greeting.isNotEmpty()) {
            "$greeting $cleanBody"
        } else {
            cleanBody
        }
    }

    private fun resolveDuePhraseMillis(duePhrase: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
        val raw = duePhrase?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val lower = raw.lowercase(Locale.US)
        if (lower == "today") return null
        AssistantParser.extractTimeMillis(raw, nowMillis)?.let { return it }
        return when {
            lower.contains("this week") || lower.contains("next week") -> nowMillis + 7L * 86_400_000L
            lower.contains("this month") || lower.contains("next month") -> nowMillis + 30L * 86_400_000L
            lower.contains("this year") -> {
                Calendar.getInstance().apply {
                    timeInMillis = nowMillis
                    set(Calendar.MONTH, Calendar.DECEMBER)
                    set(Calendar.DAY_OF_MONTH, 31)
                    set(Calendar.HOUR_OF_DAY, 18)
                    set(Calendar.MINUTE, 0)
                }.timeInMillis
            }
            else -> null
        }
    }

    private suspend fun buildConcisePendingListReply(suppressFollowUps: Boolean): String {
        val pendingTasks = db.itemDao().getPendingItemsDirect()
            .filter { !it.sourceText.startsWith("web_pipeline_") }
        return if (pendingTasks.isEmpty()) {
            if (suppressFollowUps) {
                "You have no uncompleted tasks pending right now."
            } else {
                "You have no uncompleted tasks pending right now. Want to add one for today?"
            }
        } else {
            val titles = pendingTasks.take(5).map { it.title.trim() }.filter { it.isNotEmpty() }
            val extraCount = (pendingTasks.size - titles.size).coerceAtLeast(0)
            val listText = when (titles.size) {
                1 -> titles[0]
                2 -> "${titles[0]} and ${titles[1]}"
                else -> titles.dropLast(1).joinToString(", ") + ", and " + titles.last()
            } + if (extraCount > 0) " (plus $extraCount more)" else ""

            val followUp = when {
                suppressFollowUps -> ""
                pendingTasks.size == 1 -> " Want help tackling it now?"
                else -> " Want help with either?"
            }
            "You still have $listText pending.$followUp"
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
            val name = prefs.userName.ifBlank { "Israel" }

            // Intercept active voice/on-screen confirmation replies first
            val activePending = _pendingConfirmation.value
            if (activePending != null) {
                val lowerReply = cleaned.lowercase(Locale.US).trimEnd('.', '!', '?')
                val yesAndMarkWon = lowerReply in setOf(
                    "yes mark won",
                    "yes and mark won",
                    "confirm and mark won",
                    "mark won",
                    "yes won"
                )
                if (yesAndMarkWon || MoneyAndLeadParser.isAffirmativeConfirmation(cleaned)) {
                    executePendingConfirmationInternal(activePending, alsoMarkLeadWon = yesAndMarkWon)
                    return@launch
                }
                if (MoneyAndLeadParser.isNegativeConfirmation(cleaned)) {
                    _pendingConfirmation.value = null
                    respond("Cancelled — nothing was saved.", speak = true)
                    return@launch
                }
            }

            // Handle "that's all", "no more", "let me rest" stop-follow-ups phrases
            if (AssistantParser.isStopQuestionsPhrase(cleaned)) {
                suppressFollowUpQuestions = true
                val lowerClean = cleaned.lowercase(Locale.US).trimEnd('.', '!', '?')
                val standaloneStop = lowerClean in setOf(
                    "that's all",
                    "thats all",
                    "that is all",
                    "that will be all",
                    "no more",
                    "no more questions",
                    "let me rest",
                    "i want to rest",
                    "let me sleep",
                    "nothing else",
                    "stop asking"
                )
                if (standaloneStop) {
                    respond("Got it. Get some rest.", speak = true)
                    return@launch
                }
            } else if (cleaned.endsWith("?")) {
                suppressFollowUpQuestions = false
            }

            val intent = AssistantParser.parse(cleaned)

            when (intent) {
                is ParsedIntent.WhoIsDueToday -> {
                    val allLeads = db.leadDao().getAllLeadsDirect()
                    val pending = db.itemDao().getPendingItemsDirect()
                    val dueToday = LeadCadenceEngine.computeDueFollowUpsToday(allLeads, pending)
                    val speech = if (dueToday.isEmpty()) {
                        "You have no lead follow-ups due today, $name."
                    } else {
                        val header = "You have ${dueToday.size} ${if (dueToday.size == 1) "follow-up" else "follow-ups"} due today. "
                        header + dueToday.joinToString(" ") { it.displayLine }
                    }
                    respond(speech, speak = true)
                }

                is ParsedIntent.AddLeadVoice -> {
                    val phoneClause = if (intent.phone.isNotBlank()) " with phone ${intent.phone}" else ""
                    val prompt = "Add lead ${intent.name} for ${intent.service.lowercase(Locale.US)}$phoneClause? Say yes or tap Confirm."
                    _pendingConfirmation.value = PendingConfirmation.AddLead(
                        name = intent.name,
                        service = intent.service,
                        phone = intent.phone,
                        observation = intent.observation,
                        promptText = prompt
                    )
                    respond(prompt, speak = true)
                }

                is ParsedIntent.LeadStageVoice -> {
                    val matched = repository.findBestMatchingLead(intent.leadName)
                    if (matched == null) {
                        respond(
                            "I couldn't find a lead matching '${intent.leadName}'. Say 'add lead ${intent.leadName}, website' first, or check the Leads tab.",
                            speak = true
                        )
                    } else {
                        val prompt = when (intent.targetStage) {
                            LeadCadenceEngine.STAGE_REPLIED ->
                                "Mark ${matched.name} as Replied, pause cadence, and schedule a quote within 24 hours? Say yes or tap Confirm."
                            LeadCadenceEngine.STAGE_QUOTED -> {
                                val amtLabel = intent.quoteAmount?.let {
                                    " at ${LeadCadenceEngine.formatWithCommas(it)} naira"
                                }.orEmpty()
                                "Mark ${matched.name} as Quoted$amtLabel and schedule Day 2 and Day 5 follow-ups? Say yes or tap Confirm."
                            }
                            LeadCadenceEngine.STAGE_WON ->
                                "Mark ${matched.name} as Won and cancel all remaining follow-up alarms? Say yes or tap Confirm."
                            else ->
                                "Mark ${matched.name} as Lost and cancel all remaining follow-up alarms? Say yes or tap Confirm."
                        }
                        _pendingConfirmation.value = PendingConfirmation.UpdateLeadStage(
                            leadId = matched.id,
                            leadName = matched.name,
                            targetStage = intent.targetStage,
                            quoteAmount = intent.quoteAmount,
                            promptText = prompt
                        )
                        respond(prompt, speak = true)
                    }
                }

                is ParsedIntent.LogPaymentVoice -> {
                    val matchedLead = repository.findBestMatchingLead(intent.clientName)
                    val displayClient = matchedLead?.name ?: intent.clientName
                    val canMarkWon = matchedLead != null &&
                        matchedLead.stage != LeadCadenceEngine.STAGE_WON &&
                        matchedLead.stage != LeadCadenceEngine.STAGE_LOST
                    val wonOffer = when {
                        matchedLead?.stage == LeadCadenceEngine.STAGE_QUOTED ->
                            " ${matchedLead.name} is currently Quoted — tap 'Confirm & Mark Won' or say 'yes mark won' to also close the lead."
                        canMarkWon ->
                            " Matched lead ${matchedLead?.name} (${matchedLead?.stage})."
                        else -> ""
                    }
                    val prompt = "Log ${LeadCadenceEngine.formatWithCommas(intent.amount)} naira from $displayClient as ${intent.paymentType.lowercase(Locale.US)} payment?$wonOffer"
                    _pendingConfirmation.value = PendingConfirmation.LogPayment(
                        clientName = displayClient,
                        matchedLeadId = matchedLead?.id,
                        matchedLeadName = matchedLead?.name,
                        matchedLeadStage = matchedLead?.stage,
                        amount = intent.amount,
                        paymentType = intent.paymentType,
                        note = intent.note,
                        canMarkWon = canMarkWon,
                        promptText = prompt
                    )
                    respond(prompt, speak = true)
                }

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
                        db.emailDao().getActiveUnreadDirect(1).isEmpty()
                    ) {
                        _isThinking.value = true
                        EmailHelper.syncUnreadEmailsAndNotify(appContext, postNotifications = false)
                        _isThinking.value = false
                    }
                    val emailSummary = repository.buildUnreadEmailSpokenSummary()
                    respond(emailSummary, speak = true)
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
                            val parsedFollowUp = LeadCadenceEngine.parseFollowUpSourceText(matchingItem.sourceText)
                            if (parsedFollowUp != null) {
                                LeadCadenceEngine.completeDueFollowUpStep(
                                    context = appContext,
                                    db = db,
                                    leadId = parsedFollowUp.first,
                                    reminderItemId = matchingItem.id
                                )
                            } else {
                                repository.updateItemStatus(matchingItem.id, "COMPLETED")
                            }
                            refreshDailyPlanText()
                            refreshWeeklySummary()
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
                    val cleanedTaskTitle = intent.title
                        .replace(
                            Regex(",?\\s*(?:that's all|thats all|no more|let me rest)$", RegexOption.IGNORE_CASE),
                            ""
                        )
                        .trim()
                        .ifBlank { intent.title }

                    val saveResult = repository.createTaskVerified(
                        context = appContext,
                        title = cleanedTaskTitle,
                        dueAt = intent.dueAt,
                        durationMinutes = intent.durationMinutes,
                        priority = intent.priority,
                        type = "TASK",
                        sourceText = intent.sourceText
                    )

                    saveResult.fold(
                        onSuccess = { saved ->
                            _activeProposal.value = null
                            refreshDailyPlanText()
                            refreshWeeklySummary()
                            val nextStepQuestion = if (suppressFollowUpQuestions) {
                                ""
                            } else {
                                " Want me to break this into steps you can do today, set a reminder, or attach it to a bigger goal?"
                            }
                            val body = "Added '${saved.title}' to Today's plan.$nextStepQuestion"
                            respond(
                                applyDeterministicGreeting(body, name, allowGreeting = true),
                                speak = true
                            )
                        },
                        onFailure = { err ->
                            respond(
                                "I tried to add '${cleanedTaskTitle}', but saving to your plan failed: ${err.message ?: "database write error"}.",
                                speak = true
                            )
                        }
                    )
                }

                is ParsedIntent.ChatOrQuestion -> {
                    if (securePrefs.isImapEnabled &&
                        securePrefs.imapUser.isNotBlank() &&
                        securePrefs.imapPassword.isNotBlank() &&
                        db.emailDao().getActiveUnreadDirect(1).isEmpty() &&
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
        val displayName = prefs.userName.ifBlank { "Israel" }
        val isListQuery = AssistantParser.isWhatsOnMyListQuery(userMessage)

        if (securePrefs.isAiEnabled || securePrefs.hasGroqKey()) {
            val storedGroqKey = securePrefs.getKeyForProvider(LlmProvider.GROQ)
            if (storedGroqKey.isNullOrBlank()) {
                if (isListQuery) {
                    respond(buildConcisePendingListReply(suppressFollowUpQuestions), speak = true)
                    return
                }
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
                securePrefs = securePrefs,
                suppressFollowUps = suppressFollowUpQuestions,
                onCreateTask = { title, duePhrase, priority ->
                    val dueMillis = resolveDuePhraseMillis(duePhrase)
                    val verifiedResult = repository.createTaskVerified(
                        context = appContext,
                        title = title,
                        dueAt = dueMillis,
                        durationMinutes = 30,
                        priority = priority ?: "NORMAL",
                        type = "TASK",
                        sourceText = "coach_tool:$userMessage"
                    )
                    if (verifiedResult.isSuccess) {
                        _activeProposal.value = null
                        refreshDailyPlanText()
                        refreshWeeklySummary()
                    }
                    verifiedResult
                }
            )
            _isThinking.value = false

            result.fold(
                onSuccess = { aiResp ->
                    _llmTestIsError.value = false
                    _llmTestDiagnosticText.value =
                        "HTTP 200 OK [${aiResp.providerUsed.name}/${aiResp.modelUsed}]: ${aiResp.replyText}"
                    val finalReply = applyDeterministicGreeting(
                        body = aiResp.replyText,
                        userName = displayName,
                        allowGreeting = !isListQuery && !suppressFollowUpQuestions
                    )
                    respond(finalReply, speak = true)
                },
                onFailure = { err ->
                    val exactDiag = AiClient.formatThrowableDiagnostic(err)
                    _llmTestIsError.value = true
                    _llmTestDiagnosticText.value = exactDiag
                    if (isListQuery) {
                        respond(buildConcisePendingListReply(suppressFollowUpQuestions), speak = true)
                    } else {
                        repository.recordTurn(
                            "ASSISTANT",
                            "${AiClient.AI_BRAIN_UNREACHABLE_MESSAGE}\n[Diagnostic: $exactDiag]"
                        )
                        speakText(AiClient.AI_BRAIN_UNREACHABLE_MESSAGE)
                    }
                }
            )
        } else {
            if (isListQuery) {
                respond(buildConcisePendingListReply(suppressFollowUpQuestions), speak = true)
            } else {
                val pendingReply = buildConcisePendingListReply(suppressFollowUps = true)
                val body = "$pendingReply Enable Groq AI in Settings for full coach conversation, or say 'add a task: ...' to save a task."
                respond(
                    applyDeterministicGreeting(body, displayName, allowGreeting = !suppressFollowUpQuestions),
                    speak = true
                )
            }
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
            _statusBanner.value = "Added email to Tasks and raised sender priority."
            speakText("Added '${email.extractedTaskTitle ?: email.subject}' to your tasks, $name.")
        }
    }

    fun toggleEmailTag(email: UnreadEmailEntity, tag: String) {
        viewModelScope.launch {
            repository.toggleEmailTag(email, tag)
            refreshDailyPlanText()
            _statusBanner.value = "Updated tag '$tag' on '${email.subject.take(40)}'."
        }
    }

    fun createAndApplyCustomTag(email: UnreadEmailEntity, rawTag: String) {
        val clean = rawTag.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val savedTag = repository.createAndApplyCustomTag(email, clean)
            if (savedTag != null) {
                refreshDailyPlanText()
                _statusBanner.value = "Created & applied tag '$savedTag' (sender priority updated)."
            }
        }
    }

    fun deleteCustomEmailTag(tagName: String) {
        viewModelScope.launch {
            repository.deleteCustomEmailTag(tagName)
            _statusBanner.value = "Removed custom tag '$tagName'."
        }
    }

    fun togglePrioritySender(email: UnreadEmailEntity) {
        viewModelScope.launch {
            val isNowPriority = repository.togglePrioritySender(email)
            refreshDailyPlanText()
            _statusBanner.value = if (isNowPriority) {
                "Marked ${email.sender} as a Priority Sender (High)."
            } else {
                "Removed manual Priority Sender override for ${email.sender}."
            }
        }
    }

    /**
     * Hides the email card inside the app ONLY. Never marks, moves, or deletes anything on IMAP.
     * After 3 dismissals from the same sender, automatically lowers that sender to Low.
     */
    fun dismissEmailInAppOnly(email: UnreadEmailEntity) {
        viewModelScope.launch {
            val loweredToLow = repository.dismissEmailInApp(email)
            refreshDailyPlanText()
            _statusBanner.value = if (loweredToLow) {
                "Hidden in app only (untouched on mail server). Lowered ${email.sender} to Low after 3 dismissals."
            } else {
                "Hidden in app only — never marked, moved, or deleted on mail server."
            }
        }
    }

    fun scheduleEmail30MinReminder(email: UnreadEmailEntity) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val eventMs = email.eventTimeMillis ?: (now + 31 * 60_000L)
            val rawTrigger = eventMs - 30 * 60_000L
            val triggerAt = if (rawTrigger <= now + 15_000L) now + 60_000L else rawTrigger

            val eventClock = SimpleDateFormat("EEE d MMM h:mm a", Locale.getDefault()).format(Date(eventMs))
            val reminderTitle = "In 30m: ${email.subject}"
            val itemId = repository.saveItem(
                Item(
                    type = "REMINDER",
                    title = reminderTitle,
                    details = "From ${email.sender} (Event at $eventClock). ${email.snippet}".take(260),
                    dueAt = triggerAt,
                    priority = "HIGH",
                    status = "PENDING",
                    sourceText = "email_invite_${email.id}"
                )
            )
            val savedItem = db.itemDao().getItemById(itemId)
            if (savedItem != null) {
                AlarmScheduler.scheduleItemReminder(appContext, savedItem)
            }
            repository.markEmailReminderCreated(email.id)
            refreshDailyPlanText()
            val triggerClock = SimpleDateFormat("EEE d MMM h:mm a", Locale.getDefault()).format(Date(triggerAt))
            _statusBanner.value = "Reminder scheduled for $triggerClock (30m before '${email.subject.take(36)}')."
        }
    }

    // --- Priority Senders & Learned Rules Management ---

    fun saveOrUpdateSenderRule(senderInput: String, displayName: String, priority: String) {
        if (senderInput.isBlank()) return
        viewModelScope.launch {
            repository.saveOrUpdateSenderRule(senderInput, displayName, priority)
            refreshDailyPlanText()
            _statusBanner.value = "Saved sender rule '$senderInput' -> ${priority.uppercase(Locale.US)}."
        }
    }

    fun setSenderRulePriority(rule: EmailSenderRuleEntity, priority: String) {
        viewModelScope.launch {
            repository.updateSenderRulePriority(rule, priority)
            refreshDailyPlanText()
            val label = rule.senderDisplayName.ifBlank { rule.senderAddress.ifBlank { rule.senderKey } }
            _statusBanner.value = "Set $label priority to ${priority.uppercase(Locale.US)}."
        }
    }

    fun deleteSenderRule(rule: EmailSenderRuleEntity) {
        viewModelScope.launch {
            repository.deleteSenderRule(rule)
            refreshDailyPlanText()
            val label = rule.senderDisplayName.ifBlank { rule.senderAddress.ifBlank { rule.senderKey } }
            _statusBanner.value = "Removed learned rule for $label."
        }
    }

    fun removeLearnedKeyword(rule: EmailSenderRuleEntity, keyword: String) {
        viewModelScope.launch {
            repository.removeKeywordFromSenderRule(rule, keyword)
            refreshDailyPlanText()
        }
    }

    fun clearLearnedKeywords(rule: EmailSenderRuleEntity) {
        viewModelScope.launch {
            repository.clearKeywordsOnSenderRule(rule)
            refreshDailyPlanText()
            _statusBanner.value = "Cleared learned keywords for ${rule.senderDisplayName.ifBlank { rule.senderKey }}."
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
        val gauge = revenueGauge.value
        val name = prefsState.value.userName.ifBlank { "King Israel" }
        return buildString {
            appendLine("$name - 7-Day Execution Review ($todayDateString)")
            appendLine("Yearly Target: NGN ${String.format(Locale.US, "%,d", g.yearlyGoal)} | YTD: NGN ${String.format(Locale.US, "%,d", gauge.ytdCash)}")
            appendLine("- Outreach Messages: ${w.messagesCount} / ${w.messagesTarget}")
            appendLine("- Active Conversations: ${w.conversationsCount} / ${w.conversationsTarget}")
            appendLine("- Quotes Sent: ${w.quotesCount} / ${w.quotesTarget}")
            appendLine("- Deals Won This Week: ${w.winsCount}")
            appendLine("- Cash Logged This Week: NGN ${String.format(Locale.US, "%,d", w.weekCashNgn)}")
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
        EmailHelper.syncPersistedHealth(securePrefs)
        AlarmScheduler.syncAlarmTelemetry(appContext)
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
            refreshDailyPlanText()
            _statusBanner.value = result.fold(
                onSuccess = { list ->
                    val highCount = list.count { it.priority == "HIGH" && !it.dismissedInApp }
                    val normalCount = list.count { it.priority == "NORMAL" && !it.dismissedInApp }
                    val lowCount = list.count { it.priority == "LOW" && !it.dismissedInApp }
                    "Synced ${list.size} unread (${highCount} High, ${normalCount} Normal, ${lowCount} Low)."
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

    // --- System Health One-Tap Fix Helpers ---

    fun rescheduleAllAlarmsNow() {
        viewModelScope.launch {
            val prefs = userPreferencesDataStore.getSnapshot()
            val wake = prefs.wakeUpTime.ifBlank { "07:30" }
            val schedules = db.reminderDao().getAllRemindersDirect()
            val pending = db.itemDao().getPendingItemsDirect()
            AlarmScheduler.scheduleAllActive(appContext, wake, schedules, pending)
            AlarmScheduler.syncAlarmTelemetry(appContext)
            _statusBanner.value = "Rescheduled wake-up ($wake), daily briefings, and pending reminders."
        }
    }

    fun clearCloudVoicePauseAndTest() {
        securePrefs.clearElevenLabsPause()
        VoiceEngine.syncPersistedCloudState(securePrefs)
        if (securePrefs.hasElevenLabsKey()) {
            val displayName = prefsState.value.userName.ifBlank { "King Israel" }
            speakText("${TtsGreetingHelper.getTimeAwareGreeting(displayName)} Cloud voice resumed.")
            _statusBanner.value = "Cleared 24-hour cloud voice pause and started voice check."
        } else {
            VoiceEngine.updateExternalDiagnostic(
                "Cloud voice pause cleared. Save an ElevenLabs API key or use Android TTS 1.0x.",
                isError = false
            )
            _statusBanner.value = "Cloud voice pause cleared."
        }
    }

    fun enableAndSyncImapNow() {
        if (securePrefs.imapUser.isNotBlank() && securePrefs.imapPassword.isNotBlank()) {
            securePrefs.isImapEnabled = true
            EmailHelper.schedulePeriodicChecks(appContext, securePrefs.isImapWifiOnly)
            syncEmailsNow()
        } else {
            _statusBanner.value = "Enter your IMAP email address and app password in Smart Email Checker below."
        }
    }

    fun startOrRestartWakeWordFromHealth() {
        securePrefs.wakeWordName = "Jarvis"
        securePrefs.isWakeWordEnabled = true
        _wakeWordEnabledState.value = true
        WakeWordService.stop(appContext)
        WakeWordService.start(appContext, initiatedByUserTap = true)
        _statusBanner.value = "Restarted 'Hey Jarvis' wake-word listener."
    }

    // --- Leads, Automatic Cadence, Message Templates & Payments Actions ---

    fun confirmPendingAction(alsoMarkLeadWon: Boolean = false) {
        val pending = _pendingConfirmation.value ?: return
        viewModelScope.launch {
            executePendingConfirmationInternal(pending, alsoMarkLeadWon)
        }
    }

    fun cancelPendingConfirmation() {
        _pendingConfirmation.value = null
        _statusBanner.value = "Action cancelled."
    }

    private suspend fun executePendingConfirmationInternal(
        pending: PendingConfirmation,
        alsoMarkLeadWon: Boolean
    ) {
        _pendingConfirmation.value = null
        when (pending) {
            is PendingConfirmation.AddLead -> {
                val created = repository.createLead(
                    context = appContext,
                    name = pending.name,
                    service = pending.service,
                    phone = pending.phone,
                    source = "Voice / Chat",
                    observation = pending.observation,
                    startContactedCadence = false
                )
                refreshDailyPlanText()
                refreshWeeklySummary()
                val msg = "Added lead ${created.name} (${created.service})."
                _statusBanner.value = msg
                respond(msg, speak = true)
            }

            is PendingConfirmation.UpdateLeadStage -> {
                when (pending.targetStage) {
                    LeadCadenceEngine.STAGE_REPLIED -> {
                        LeadCadenceEngine.markLeadReplied(
                            context = appContext,
                            db = db,
                            leadId = pending.leadId
                        )
                        val msg = "Marked ${pending.leadName} as Replied and scheduled a quote task within 24 hours."
                        _statusBanner.value = msg
                        respond(msg, speak = true)
                    }
                    LeadCadenceEngine.STAGE_QUOTED -> {
                        LeadCadenceEngine.markLeadQuoted(
                            context = appContext,
                            db = db,
                            leadId = pending.leadId,
                            quoteAmount = pending.quoteAmount
                        )
                        val amtText = pending.quoteAmount?.let {
                            " at ₦${LeadCadenceEngine.formatWithCommas(it)}"
                        }.orEmpty()
                        val msg = "Marked ${pending.leadName} as Quoted$amtText and scheduled Day 2 and Day 5 follow-ups."
                        _statusBanner.value = msg
                        respond(msg, speak = true)
                    }
                    LeadCadenceEngine.STAGE_WON -> {
                        LeadCadenceEngine.markLeadClosed(
                            context = appContext,
                            db = db,
                            leadId = pending.leadId,
                            won = true
                        )
                        val msg = "Congratulations! Marked ${pending.leadName} as Won and cancelled remaining follow-ups."
                        _statusBanner.value = msg
                        respond(msg, speak = true)
                    }
                    LeadCadenceEngine.STAGE_LOST -> {
                        LeadCadenceEngine.markLeadClosed(
                            context = appContext,
                            db = db,
                            leadId = pending.leadId,
                            won = false
                        )
                        val msg = "Marked ${pending.leadName} as Lost and cancelled remaining follow-ups."
                        _statusBanner.value = msg
                        respond(msg, speak = true)
                    }
                }
                refreshDailyPlanText()
                refreshWeeklySummary()
            }

            is PendingConfirmation.LogPayment -> {
                val payment = repository.addPayment(
                    clientName = pending.clientName,
                    amount = pending.amount,
                    type = pending.paymentType,
                    note = pending.note,
                    explicitLeadId = pending.matchedLeadId
                )
                if (alsoMarkLeadWon && pending.matchedLeadId != null) {
                    LeadCadenceEngine.markLeadClosed(
                        context = appContext,
                        db = db,
                        leadId = pending.matchedLeadId,
                        won = true,
                        note = "Payment of ₦${LeadCadenceEngine.formatWithCommas(payment.amount)} received"
                    )
                }
                refreshDailyPlanText()
                refreshWeeklySummary()

                val goal = db.goalDao().getGoalsDirect() ?: Goal()
                val allPayments = db.paymentDao().getAllPaymentsDirect()
                val gauge = LeadCadenceEngine.computeRevenueGauge(goal, allPayments)
                val wonSuffix = if (alsoMarkLeadWon && pending.matchedLeadName != null) {
                    " Marked ${pending.matchedLeadName} as Won."
                } else {
                    ""
                }
                val msg = "Logged ₦${LeadCadenceEngine.formatWithCommas(payment.amount)} from ${payment.clientName} (${payment.type}).$wonSuffix ${gauge.homeGaugeLine}"
                _statusBanner.value = "Logged ₦${LeadCadenceEngine.formatWithCommas(payment.amount)} from ${payment.clientName}."
                respond(msg, speak = true)
            }
        }
    }

    fun createLead(
        name: String,
        service: String,
        phone: String = "",
        source: String = "",
        referredBy: String = "",
        observation: String = "",
        notes: String = "",
        startContactedCadence: Boolean = false
    ) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return
        viewModelScope.launch {
            val saved = repository.createLead(
                context = appContext,
                name = cleanName,
                service = service,
                phone = phone,
                source = source,
                referredBy = referredBy,
                observation = observation,
                notes = notes,
                startContactedCadence = startContactedCadence
            )
            refreshDailyPlanText()
            refreshWeeklySummary()
            _statusBanner.value = if (startContactedCadence) {
                "Added ${saved.name} (${saved.service}) & scheduled Day 0 / 2 / 5 / 10 cadence."
            } else {
                "Added lead ${saved.name} (${saved.service})."
            }
        }
    }

    fun createLeadFromEmail(
        email: UnreadEmailEntity,
        name: String,
        service: String,
        phone: String,
        observation: String
    ) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return
        viewModelScope.launch {
            val emailNotes = "Email subject: ${email.subject}. ${email.snippet}".take(300)
            val saved = repository.createLead(
                context = appContext,
                name = cleanName,
                service = service,
                phone = phone,
                source = "Email (${email.senderAddress.ifBlank { email.sender }})",
                observation = observation,
                notes = emailNotes,
                startContactedCadence = false
            )
            refreshDailyPlanText()
            refreshWeeklySummary()
            _statusBanner.value = "Added lead '${saved.name}' from email."
        }
    }

    fun updateLeadDetails(lead: Lead) {
        viewModelScope.launch {
            repository.updateLeadDetails(lead)
            refreshDailyPlanText()
            refreshWeeklySummary()
            _statusBanner.value = "Saved changes for ${lead.name}."
        }
    }

    fun addLeadNote(leadId: Long, noteText: String) {
        val clean = noteText.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            repository.addLeadNoteEvent(leadId, clean)
            _statusBanner.value = "Added note to lead history."
        }
    }

    fun deleteLead(lead: Lead) {
        viewModelScope.launch {
            repository.deleteLead(appContext, lead)
            refreshDailyPlanText()
            refreshWeeklySummary()
            _statusBanner.value = "Deleted lead ${lead.name}."
        }
    }

    fun markLeadContacted(leadId: Long, day0AlreadySent: Boolean = false) {
        viewModelScope.launch {
            LeadCadenceEngine.markLeadContacted(
                context = appContext,
                db = db,
                leadId = leadId,
                day0AlreadySent = day0AlreadySent
            )
            refreshDailyPlanText()
            refreshWeeklySummary()
            val lead = db.leadDao().getLeadById(leadId)
            _statusBanner.value = "Marked ${lead?.name ?: "lead"} CONTACTED — scheduled Day 0, 2, 5, and 10 alarms."
        }
    }

    fun markLeadReplied(leadId: Long, note: String = "") {
        viewModelScope.launch {
            LeadCadenceEngine.markLeadReplied(
                context = appContext,
                db = db,
                leadId = leadId,
                note = note
            )
            refreshDailyPlanText()
            refreshWeeklySummary()
            val lead = db.leadDao().getLeadById(leadId)
            _statusBanner.value = "Marked ${lead?.name ?: "lead"} REPLIED — quote task scheduled within 24h."
        }
    }

    fun logLeadQuote(leadId: Long, rawAmountInput: String, note: String = "") {
        viewModelScope.launch {
            val parsedAmount = MoneyAndLeadParser.parseNairaAmount(rawAmountInput)
                ?: rawAmountInput.replace(",", "").trim().toLongOrNull()
            LeadCadenceEngine.markLeadQuoted(
                context = appContext,
                db = db,
                leadId = leadId,
                quoteAmount = parsedAmount,
                note = note
            )
            refreshDailyPlanText()
            refreshWeeklySummary()
            val lead = db.leadDao().getLeadById(leadId)
            val amtText = parsedAmount?.let { " (₦${LeadCadenceEngine.formatWithCommas(it)})" }.orEmpty()
            _statusBanner.value = "Marked ${lead?.name ?: "lead"} QUOTED$amtText — Day 2 & Day 5 follow-ups scheduled."
        }
    }

    fun markLeadWon(leadId: Long, note: String = "") {
        viewModelScope.launch {
            LeadCadenceEngine.markLeadClosed(
                context = appContext,
                db = db,
                leadId = leadId,
                won = true,
                note = note
            )
            refreshDailyPlanText()
            refreshWeeklySummary()
            val lead = db.leadDao().getLeadById(leadId)
            _statusBanner.value = "Marked ${lead?.name ?: "lead"} WON — follow-up alarms cancelled."
        }
    }

    fun markLeadLost(leadId: Long, note: String = "") {
        viewModelScope.launch {
            LeadCadenceEngine.markLeadClosed(
                context = appContext,
                db = db,
                leadId = leadId,
                won = false,
                note = note
            )
            refreshDailyPlanText()
            refreshWeeklySummary()
            val lead = db.leadDao().getLeadById(leadId)
            _statusBanner.value = "Marked ${lead?.name ?: "lead"} LOST — follow-up alarms cancelled."
        }
    }

    fun completeDueFollowUp(due: DueFollowUpUiModel) {
        viewModelScope.launch {
            LeadCadenceEngine.completeDueFollowUpStep(
                context = appContext,
                db = db,
                leadId = due.lead.id,
                reminderItemId = due.reminderItemId
            )
            refreshDailyPlanText()
            refreshWeeklySummary()
            _statusBanner.value = "Completed ${LeadCadenceEngine.stepShortLabel(due.stepKey)} for ${due.lead.name}."
        }
    }

    fun snoozeDueFollowUp(due: DueFollowUpUiModel) {
        viewModelScope.launch {
            LeadCadenceEngine.snoozeDueFollowUpStep(
                context = appContext,
                db = db,
                leadId = due.lead.id,
                reminderItemId = due.reminderItemId
            )
            refreshDailyPlanText()
            _statusBanner.value = "Snoozed ${due.lead.name} follow-up to tomorrow 9:00 AM."
        }
    }

    fun loadDraftPreview(
        lead: Lead,
        stepKey: String?,
        useAiRewrite: Boolean,
        onLoaded: (String) -> Unit
    ) {
        viewModelScope.launch {
            val draft = LeadCadenceEngine.buildDraftForLead(
                db = db,
                securePrefs = securePrefs,
                lead = lead,
                stepKey = stepKey,
                allowAiRewrite = useAiRewrite
            )
            onLoaded(draft)
        }
    }

    fun openLeadWhatsAppWithDraft(
        activityContext: Context,
        lead: Lead,
        stepKey: String?,
        updatedObservation: String? = null,
        customDraftOverride: String? = null
    ) {
        viewModelScope.launch {
            var currentLead = db.leadDao().getLeadById(lead.id) ?: lead
            if (!updatedObservation.isNullOrBlank() && updatedObservation.trim() != currentLead.observation) {
                currentLead = currentLead.copy(observation = updatedObservation.trim())
                repository.updateLeadDetails(currentLead)
            }
            val effectiveStep = stepKey ?: currentLead.nextActionType ?: LeadCadenceEngine.STEP_DAY_0_MESSAGE
            val draft = customDraftOverride?.takeIf { it.isNotBlank() }
                ?: LeadCadenceEngine.buildDraftForLead(
                    db = db,
                    securePrefs = securePrefs,
                    lead = currentLead,
                    stepKey = effectiveStep,
                    allowAiRewrite = true
                )

            val opened = LeadCadenceEngine.openWhatsAppWithDraft(
                context = activityContext,
                phone = currentLead.phone,
                draft = draft
            )
            if (!opened) {
                _statusBanner.value = "Could not open WhatsApp or browser on this device."
            }
        }
    }

    fun callLead(activityContext: Context, lead: Lead) {
        if (lead.phone.isBlank()) {
            _statusBanner.value = "No phone number saved for ${lead.name}. Edit the lead to add a phone number."
            return
        }
        val opened = LeadCadenceEngine.openDialerForLead(activityContext, lead.phone)
        if (!opened) {
            _statusBanner.value = "Unable to open phone dialer on this device."
        }
    }

    fun saveMessageTemplate(template: MessageTemplateEntity) {
        viewModelScope.launch {
            repository.saveMessageTemplate(template)
            _statusBanner.value = "Saved template: ${template.title}."
        }
    }

    fun resetMessageTemplates() {
        viewModelScope.launch {
            repository.resetMessageTemplatesToDefaults()
            _statusBanner.value = "Reset all follow-up message templates to defaults."
        }
    }

    fun addManualPayment(
        clientName: String,
        rawAmountInput: String,
        type: String,
        note: String,
        alsoMarkMatchedLeadWon: Boolean = false
    ) {
        val cleanClient = clientName.trim()
        if (cleanClient.isEmpty()) return
        val amount = MoneyAndLeadParser.parseNairaAmount(rawAmountInput)
            ?: rawAmountInput.replace(",", "").trim().toLongOrNull()
        if (amount == null || amount <= 0L) {
            _statusBanner.value = "Enter a valid amount (for example 150k or 250,000)."
            return
        }
        viewModelScope.launch {
            val matchedLead = repository.findBestMatchingLead(cleanClient)
            val saved = repository.addPayment(
                clientName = matchedLead?.name ?: cleanClient,
                amount = amount,
                type = type,
                note = note,
                explicitLeadId = matchedLead?.id
            )
            if (alsoMarkMatchedLeadWon && matchedLead != null && matchedLead.stage != LeadCadenceEngine.STAGE_WON) {
                LeadCadenceEngine.markLeadClosed(
                    context = appContext,
                    db = db,
                    leadId = matchedLead.id,
                    won = true,
                    note = "Logged payment of ₦${LeadCadenceEngine.formatWithCommas(amount)}"
                )
            }
            refreshDailyPlanText()
            refreshWeeklySummary()
            _statusBanner.value = "Logged ₦${LeadCadenceEngine.formatWithCommas(saved.amount)} from ${saved.clientName} (${saved.type})."
        }
    }

    fun updatePayment(payment: Payment) {
        viewModelScope.launch {
            repository.updatePayment(payment)
            refreshDailyPlanText()
            refreshWeeklySummary()
            _statusBanner.value = "Updated payment for ${payment.clientName}."
        }
    }

    fun deletePayment(payment: Payment) {
        viewModelScope.launch {
            repository.deletePayment(payment)
            refreshDailyPlanText()
            refreshWeeklySummary()
            _statusBanner.value = "Deleted payment entry for ${payment.clientName}."
        }
    }
}
