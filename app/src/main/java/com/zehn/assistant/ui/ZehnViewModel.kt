package com.zehn.assistant.ui

import android.app.Application
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zehn.assistant.ZehnApplication
import com.zehn.assistant.agent.AgentLoop
import com.zehn.assistant.agent.AgentPlanState
import com.zehn.assistant.agent.CallAssistantManager
import com.zehn.assistant.agent.CallCapabilityReport
import com.zehn.assistant.agent.CallInteractionRecord
import com.zehn.assistant.agent.CameraLaunchRequest
import com.zehn.assistant.agent.FloatingBubbleService
import com.zehn.assistant.agent.PhoneControlExecutor
import com.zehn.assistant.agent.PlanManager
import com.zehn.assistant.agent.ZehnAccessibilityService
import com.zehn.assistant.agent.ZehnNotificationListenerService
import com.zehn.assistant.ai.AiEngineRouter
import com.zehn.assistant.ai.ApiProviderUsage
import com.zehn.assistant.ai.CloudLlmClient
import com.zehn.assistant.ai.GitHubRepoSuggestion
import com.zehn.assistant.ai.HuggingFaceModelSuggestion
import com.zehn.assistant.ai.LiteRtModelSpec
import com.zehn.assistant.ai.LlmMessage
import com.zehn.assistant.ai.OnDeviceLiteRtManager
import com.zehn.assistant.ai.OnDeviceModelState
import com.zehn.assistant.ai.OpenSourceDiscoveryClient
import com.zehn.assistant.ai.ProviderConnectionTestResult
import com.zehn.assistant.ai.RagEngine
import com.zehn.assistant.data.BackupManager
import com.zehn.assistant.data.PreferencesRepository
import com.zehn.assistant.data.ZehnSettings
import com.zehn.assistant.data.db.ActionLogDao
import com.zehn.assistant.data.db.ActionLogEntity
import com.zehn.assistant.data.db.ConversationDao
import com.zehn.assistant.data.db.ConversationEntity
import com.zehn.assistant.data.db.MemoryDao
import com.zehn.assistant.data.db.MemoryEntity
import com.zehn.assistant.data.db.MessageDao
import com.zehn.assistant.data.db.MessageEntity
import com.zehn.assistant.data.db.ProjectEntity
import com.zehn.assistant.data.db.ProjectRagDao
import com.zehn.assistant.data.db.SkillDao
import com.zehn.assistant.data.db.SkillEntity
import com.zehn.assistant.data.db.TriggerDao
import com.zehn.assistant.data.db.TriggerEntity
import com.zehn.assistant.data.db.WorkflowDao
import com.zehn.assistant.data.db.WorkflowEntity
import com.zehn.assistant.data.model.AiExecutionMode
import com.zehn.assistant.data.model.AiProvider
import com.zehn.assistant.data.model.LiteRtBackend
import com.zehn.assistant.data.model.MemoryTier
import com.zehn.assistant.data.model.SttEngineType
import com.zehn.assistant.data.model.TtsEngineType
import com.zehn.assistant.data.model.VadEngineType
import com.zehn.assistant.data.model.VoiceLanguage
import com.zehn.assistant.data.model.VoiceProfile
import com.zehn.assistant.security.CrashRecoveryManager
import com.zehn.assistant.security.CrashRecoveryReport
import com.zehn.assistant.security.KeystoreCryptoManager
import com.zehn.assistant.security.PendingConfirmationRequest
import com.zehn.assistant.security.SafetyPolicyManager
import com.zehn.assistant.security.SafetyPolicyState
import com.zehn.assistant.voice.LanguageDetector
import com.zehn.assistant.voice.LiveChatTranscriptEvent
import com.zehn.assistant.voice.LiveModelsDiscoveryState
import com.zehn.assistant.voice.VoicePipelineCoordinator
import com.zehn.assistant.voice.VoicePipelineUiState
import com.zehn.assistant.voice.WakeWordService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ZehnViewModel @Inject constructor(
    private val app: Application,
    private val prefs: PreferencesRepository,
    val crypto: KeystoreCryptoManager,
    val safetyPolicy: SafetyPolicyManager,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val memoryDao: MemoryDao,
    private val projectRagDao: ProjectRagDao,
    private val skillDao: SkillDao,
    private val workflowDao: WorkflowDao,
    private val triggerDao: TriggerDao,
    private val actionLogDao: ActionLogDao,
    private val aiRouter: AiEngineRouter,
    private val cloudLlmClient: CloudLlmClient,
    private val discoveryClient: OpenSourceDiscoveryClient,
    val liteRtManager: OnDeviceLiteRtManager,
    private val ragEngine: RagEngine,
    val voiceCoordinator: VoicePipelineCoordinator,
    val agentLoop: AgentLoop,
    val planManager: PlanManager,
    val phoneControl: PhoneControlExecutor,
    val callAssistant: CallAssistantManager,
    private val backupManager: BackupManager,
    private val crashRecoveryManager: CrashRecoveryManager
) : AndroidViewModel(app) {

    val settings: StateFlow<ZehnSettings> = prefs.settings.stateIn(
        viewModelScope, SharingStarted.Eagerly, ZehnSettings()
    )

    val policy: StateFlow<SafetyPolicyState> = safetyPolicy.policy
    val pendingConfirmation: StateFlow<PendingConfirmationRequest?> = safetyPolicy.pendingConfirmation
    val voiceUiState: StateFlow<VoicePipelineUiState> = voiceCoordinator.uiState
    val onDeviceState: StateFlow<OnDeviceModelState> = liteRtManager.state
    val agentPlanState: StateFlow<AgentPlanState> = planManager.planState
    val recordingActive: StateFlow<Boolean> = agentLoop.recordingActive
    val keysVersion: StateFlow<Int> = crypto.keysUpdatedVersion
    val providerUsage: StateFlow<Map<AiProvider, ApiProviderUsage>> = aiRouter.providerUsage
    val recentCallRecords: StateFlow<List<CallInteractionRecord>> = callAssistant.recentCalls
    val cameraLaunchRequests: SharedFlow<CameraLaunchRequest> = voiceCoordinator.cameraLaunchRequests

    val accessibilityConnected = ZehnAccessibilityService.instance
    val notificationListenerConnected = ZehnNotificationListenerService.connected
    val lastCrashReport: StateFlow<CrashRecoveryReport?> = crashRecoveryManager.lastCrashReport

    private val _activeConversationId = MutableStateFlow(1L)
    val activeConversationId: StateFlow<Long> = _activeConversationId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val conversations: StateFlow<List<ConversationEntity>> = _searchQuery.flatMapLatest { q ->
        if (q.isBlank()) conversationDao.observeAll() else conversationDao.search(q)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<MessageEntity>> = _activeConversationId.flatMapLatest { convId ->
        messageDao.observeForConversation(convId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val memories: StateFlow<List<MemoryEntity>> = memoryDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val projects: StateFlow<List<ProjectEntity>> = projectRagDao.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val skills: StateFlow<List<SkillEntity>> = skillDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val workflows: StateFlow<List<WorkflowEntity>> = workflowDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val triggers: StateFlow<List<TriggerEntity>> = triggerDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val actionLogs: StateFlow<List<ActionLogEntity>> = actionLogDao.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedProjectId = MutableStateFlow<Long?>(null)
    val selectedProjectId: StateFlow<Long?> = _selectedProjectId.asStateFlow()

    private val _chatBusy = MutableStateFlow(false)
    val chatBusy: StateFlow<Boolean> = _chatBusy.asStateFlow()

    private val _streamingDraft = MutableStateFlow("")
    val streamingDraft: StateFlow<String> = _streamingDraft.asStateFlow()

    private val _systemStatusText = MutableStateFlow(phoneControl.getSystemStatusSummary())
    val systemStatusText: StateFlow<String> = _systemStatusText.asStateFlow()

    private val _toastBanner = MutableStateFlow<String?>(null)
    val toastBanner: StateFlow<String?> = _toastBanner.asStateFlow()

    private val _autoTunerOutput = MutableStateFlow("")
    val autoTunerOutput: StateFlow<String> = _autoTunerOutput.asStateFlow()

    private val _crashFileText = MutableStateFlow<String?>(readCrashLogFile())
    val crashFileText: StateFlow<String?> = _crashFileText.asStateFlow()

    val liveModelsDiscovery: StateFlow<LiveModelsDiscoveryState> = voiceCoordinator.liveModelsDiscovery

    private val _liveInputDraft = MutableStateFlow("")
    val liveInputDraft: StateFlow<String> = _liveInputDraft.asStateFlow()

    private val _liveOutputDraft = MutableStateFlow("")
    val liveOutputDraft: StateFlow<String> = _liveOutputDraft.asStateFlow()

    private val _providerTestResults = MutableStateFlow<Map<AiProvider, ProviderConnectionTestResult>>(emptyMap())
    val providerTestResults: StateFlow<Map<AiProvider, ProviderConnectionTestResult>> = _providerTestResults.asStateFlow()

    private val _testingProvider = MutableStateFlow<AiProvider?>(null)
    val testingProvider: StateFlow<AiProvider?> = _testingProvider.asStateFlow()

    private val _githubResults = MutableStateFlow<List<GitHubRepoSuggestion>>(emptyList())
    val githubResults: StateFlow<List<GitHubRepoSuggestion>> = _githubResults.asStateFlow()

    private val _hfResults = MutableStateFlow<List<HuggingFaceModelSuggestion>>(emptyList())
    val hfResults: StateFlow<List<HuggingFaceModelSuggestion>> = _hfResults.asStateFlow()

    private val _discoveryLoading = MutableStateFlow(false)
    val discoveryLoading: StateFlow<Boolean> = _discoveryLoading.asStateFlow()

    init {
        viewModelScope.launch {
            val allConvs = conversationDao.getAllOnce()
            if (allConvs.isNotEmpty()) {
                _activeConversationId.value = allConvs.first().id
            }
        }
        viewModelScope.launch {
            voiceCoordinator.errors.collect { err ->
                if (err.isNotBlank()) {
                    _toastBanner.value = err
                }
            }
        }
        viewModelScope.launch {
            crypto.keysUpdatedVersion.collect {
                runCatching { voiceCoordinator.refreshAvailableLiveModels() }
            }
        }
        viewModelScope.launch {
            voiceCoordinator.liveChatEvents.collect { ev ->
                when (ev) {
                    is LiveChatTranscriptEvent.InputDraft -> _liveInputDraft.value = ev.text
                    is LiveChatTranscriptEvent.OutputDraft -> _liveOutputDraft.value = ev.text
                    is LiveChatTranscriptEvent.ClearDrafts -> {
                        _liveInputDraft.value = ""
                        _liveOutputDraft.value = ""
                    }
                    is LiveChatTranscriptEvent.CommittedTurn -> {
                        persistVoiceTranscriptToChat(ev.role, ev.content, ev.modelUsed)
                    }
                }
            }
        }
    }

    fun isLowRamSingleRequestMode(): Boolean = aiRouter.isLowRamSingleRequestMode()
    fun callCapabilityReport(): CallCapabilityReport = callAssistant.capabilityReport()

    fun testProviderConnection(provider: AiProvider) = viewModelScope.launch {
        _testingProvider.value = provider
        val res = aiRouter.testProviderConnection(provider)
        _providerTestResults.value = _providerTestResults.value + (provider to res)
        _testingProvider.value = null
        _toastBanner.value = "${provider.displayName}: ${res.message}"
    }

    fun addGeminiKeySlot(key: String) {
        if (key.isBlank()) return
        crypto.addGeminiKey(key)
        _toastBanner.value = "Added Gemini API key slot"
    }

    fun removeGeminiKeySlot(index: Int) {
        crypto.removeGeminiKeyAt(index)
        _toastBanner.value = "Removed Gemini API key slot #${index + 1}"
    }

    fun deleteProviderSecret(keyName: String, label: String) {
        crypto.deleteSecret(keyName)
        _toastBanner.value = "Deleted $label from encrypted vault"
    }

    fun searchOpenSourceTools(query: String) = viewModelScope.launch {
        _discoveryLoading.value = true
        discoveryClient.searchGitHubTools(query).onSuccess { items ->
            _githubResults.value = items
            if (items.isEmpty()) _toastBanner.value = "No GitHub repositories matched '$query'"
        }.onFailure { err ->
            _toastBanner.value = err.message ?: "GitHub search failed"
        }
        _discoveryLoading.value = false
    }

    fun searchHuggingFaceModels(query: String) = viewModelScope.launch {
        _discoveryLoading.value = true
        discoveryClient.searchHuggingFaceModels(query).onSuccess { items ->
            _hfResults.value = items
            if (items.isEmpty()) _toastBanner.value = "No Hugging Face models matched '$query'"
        }.onFailure { err ->
            _toastBanner.value = err.message ?: "Hugging Face search failed"
        }
        _discoveryLoading.value = false
    }

    private suspend fun persistVoiceTranscriptToChat(role: String, content: String, modelUsed: String) {
        val clean = content.trim()
        if (clean.isEmpty()) return
        runCatching {
            var convId = _activeConversationId.value
            val existing = conversationDao.getAllOnce().firstOrNull { it.id == convId }
            if (existing == null) {
                convId = conversationDao.insert(
                    ConversationEntity(
                        title = clean.take(36),
                        providerName = AiProvider.GEMINI.name,
                        modelName = modelUsed
                    )
                )
                _activeConversationId.value = convId
            } else if (role == "user" && existing.title == "New Chat") {
                conversationDao.update(existing.copy(title = clean.take(36), updatedAt = System.currentTimeMillis()))
            }
            messageDao.insert(
                MessageEntity(
                    conversationId = convId,
                    role = role,
                    content = clean,
                    modelUsed = if (role == "assistant") modelUsed else "",
                    detectedLanguage = LanguageDetector.detect(clean, settings.value.voiceLanguage).code
                )
            )
        }
    }

    private fun readCrashLogFile(): String? {
        return runCatching {
            val file = File(app.filesDir, ZehnApplication.CRASH_LOG_FILE_NAME)
            if (file.exists()) file.readText().takeIf { it.isNotBlank() } else null
        }.getOrNull()
    }

    fun clearCrashLogFile() {
        runCatching {
            val file = File(app.filesDir, ZehnApplication.CRASH_LOG_FILE_NAME)
            if (file.exists()) file.delete()
        }
        _crashFileText.value = null
        crashRecoveryManager.dismissCrashReport()
    }

    fun clearBanner() {
        _toastBanner.value = null
    }

    fun showBanner(msg: String) {
        _toastBanner.value = msg
    }

    fun dismissCrashReport() {
        crashRecoveryManager.dismissCrashReport()
    }

    fun refreshTelemetry() {
        _systemStatusText.value = phoneControl.getSystemStatusSummary()
        liteRtManager.refreshDeviceCapabilities()
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            prefs.updateSettings { it.copy(onboardingCompleted = true) }
        }
    }

    fun setConversationSearch(q: String) {
        _searchQuery.value = q
    }

    fun selectConversation(id: Long) {
        _activeConversationId.value = id
    }

    fun createNewConversation(projectId: Long? = _selectedProjectId.value) {
        viewModelScope.launch {
            val s = settings.value
            val id = conversationDao.insert(
                ConversationEntity(
                    title = "New Chat",
                    projectId = projectId,
                    providerName = s.activeProvider.name,
                    modelName = s.activeModel
                )
            )
            _activeConversationId.value = id
        }
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            conversationDao.deleteById(id)
            val remaining = conversationDao.getAllOnce()
            if (remaining.isEmpty()) createNewConversation()
            else if (_activeConversationId.value == id) _activeConversationId.value = remaining.first().id
        }
    }

    fun selectProject(projectId: Long?) {
        _selectedProjectId.value = projectId
    }

    fun sendChatMessage(text: String, attachedImageUri: Uri? = null, speakReply: Boolean = false) {
        val clean = text.trim()
        if (clean.isEmpty() && attachedImageUri == null) return
        if (_chatBusy.value) return

        viewModelScope.launch {
            if (safetyPolicy.policy.value.emergencyStopped) {
                _toastBanner.value = "Emergency STOP is active. Tap RESUME first."
                return@launch
            }
            _chatBusy.value = true
            _streamingDraft.value = ""

            try {
                var convId = _activeConversationId.value
                val existing = conversationDao.getAllOnce().firstOrNull { it.id == convId }
                if (existing == null) {
                    convId = conversationDao.insert(
                        ConversationEntity(
                            title = clean.take(36).ifBlank { "Vision Query" },
                            providerName = settings.value.activeProvider.name,
                            modelName = settings.value.activeModel
                        )
                    )
                    _activeConversationId.value = convId
                } else if (existing.title == "New Chat" && clean.isNotBlank()) {
                    conversationDao.update(existing.copy(title = clean.take(36), updatedAt = System.currentTimeMillis()))
                }

                val detectedLang = LanguageDetector.detect(clean, settings.value.voiceLanguage)
                messageDao.insert(
                    MessageEntity(
                        conversationId = convId,
                        role = "user",
                        content = clean.ifBlank { "Analyze attached image" },
                        imageUri = attachedImageUri?.toString(),
                        detectedLanguage = detectedLang.code
                    )
                )

                memoryDao.insert(
                    MemoryEntity(
                        tier = MemoryTier.WORKING.name,
                        title = "Last User Query",
                        content = clean.take(180),
                        priority = 6
                    )
                )

                if (attachedImageUri == null) {
                    val agentReply = agentLoop.tryExecuteCommand(clean)
                    if (agentReply != null) {
                        messageDao.insert(
                            MessageEntity(
                                conversationId = convId,
                                role = "assistant",
                                content = agentReply,
                                modelUsed = "Zehn Agent",
                                detectedLanguage = detectedLang.code
                            )
                        )
                        if (speakReply) voiceCoordinator.previewVoice(settings.value.activeVoice)
                        return@launch
                    }
                }

                val history = messageDao.getForConversationOnce(convId)
                    .takeLast(12)
                    .map { LlmMessage(it.role, it.content) }

                val replyAcc = StringBuilder()
                var citationsText = ""
                var usedModel = settings.value.activeModel
                var errMsg: String? = null

                aiRouter.routeAndStream(
                    history = history,
                    userInput = clean.ifBlank { "Describe and analyze this image carefully." },
                    projectId = _selectedProjectId.value,
                    imageUri = attachedImageUri
                ).collect { ev ->
                    if (ev.modelUsed.isNotBlank()) usedModel = ev.modelUsed
                    if (ev.citations.isNotEmpty()) {
                        citationsText = ev.citations.joinToString("\n") {
                            "• ${it.documentName} (p.${it.pageOrSection})"
                        }
                    }
                    if (ev.delta.isNotEmpty()) {
                        replyAcc.append(ev.delta)
                        _streamingDraft.value = replyAcc.toString()
                    }
                    if (ev.error != null) errMsg = ev.error
                }

                if (errMsg != null) {
                    _toastBanner.value = errMsg
                }

                val finalContent = buildString {
                    val main = replyAcc.toString().ifBlank { errMsg ?: "No response." }
                    append(main)
                    if (citationsText.isNotBlank() && errMsg == null) {
                        append("\n\nSources:\n$citationsText")
                    }
                }

                messageDao.insert(
                    MessageEntity(
                        conversationId = convId,
                        role = "assistant",
                        content = finalContent,
                        modelUsed = usedModel,
                        detectedLanguage = detectedLang.code
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _toastBanner.value = "Chat failed: ${e.message ?: "Unexpected error"}"
            } finally {
                _streamingDraft.value = ""
                _chatBusy.value = false
            }
        }
    }

    fun generateImageInChat(prompt: String) {
        if (prompt.isBlank()) return
        viewModelScope.launch {
            _chatBusy.value = true
            try {
                val convId = _activeConversationId.value
                messageDao.insert(MessageEntity(conversationId = convId, role = "user", content = "/imagine $prompt"))
                val res = cloudLlmClient.generateImage(prompt)
                res.onSuccess { path ->
                    messageDao.insert(
                        MessageEntity(
                            conversationId = convId,
                            role = "assistant",
                            content = "Generated image for \"$prompt\":",
                            imageUri = Uri.fromFile(File(path)).toString(),
                            modelUsed = "Image Generator"
                        )
                    )
                }.onFailure { e ->
                    val errText = e.message ?: "Image generation failed"
                    _toastBanner.value = errText
                    messageDao.insert(
                        MessageEntity(
                            conversationId = convId,
                            role = "assistant",
                            content = errText,
                            modelUsed = "Safety / Image"
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _toastBanner.value = "Image generation failed: ${e.message ?: "Unexpected error"}"
            } finally {
                _chatBusy.value = false
            }
        }
    }

    fun setExecutionMode(mode: AiExecutionMode) = viewModelScope.launch {
        prefs.updateSettings { it.copy(executionMode = mode) }
    }

    fun setProviderAndModel(provider: AiProvider, model: String) = viewModelScope.launch {
        prefs.updateSettings { it.copy(activeProvider = provider, activeModel = model) }
    }

    fun setVoiceProfile(profile: VoiceProfile) = viewModelScope.launch {
        prefs.updateSettings { it.copy(activeVoice = profile) }
    }

    fun setVoiceLanguage(lang: VoiceLanguage) = viewModelScope.launch {
        prefs.updateSettings { it.copy(voiceLanguage = lang) }
    }

    fun setPipelineEngines(vad: VadEngineType, stt: SttEngineType, tts: TtsEngineType) = viewModelScope.launch {
        prefs.updateSettings { it.copy(vadEngine = vad, sttEngine = stt, ttsEngine = tts) }
    }

    fun setVoiceTuning(rate: Float, pitch: Float) = viewModelScope.launch {
        prefs.updateSettings { it.copy(voiceRate = rate, voicePitch = pitch) }
    }

    fun setClonedVoiceUri(uri: String) = viewModelScope.launch {
        prefs.updateSettings {
            it.copy(
                clonedVoiceReferenceUri = uri,
                ttsEngine = if (uri.isNotBlank()) TtsEngineType.CLONED_PROFILE else TtsEngineType.ANDROID_STREAMING
            )
        }
    }

    fun setLiteRtBackend(backend: LiteRtBackend) = viewModelScope.launch {
        prefs.updateSettings { it.copy(liteRtBackend = backend) }
    }

    fun setSpeculativeDecoding(enabled: Boolean) = viewModelScope.launch {
        prefs.updateSettings { it.copy(speculativeDecodingEnabled = enabled) }
    }

    fun setAutoTuner(enabled: Boolean) = viewModelScope.launch {
        prefs.updateSettings { it.copy(autoTunerEnabled = enabled) }
    }

    fun selectAndLoadLiteRtModel(spec: LiteRtModelSpec) = viewModelScope.launch {
        prefs.updateSettings { it.copy(selectedLiteRtModelId = spec.id) }
        val ok = liteRtManager.loadModel(spec, settings.value.liteRtBackend, settings.value.personaPrompt)
        _toastBanner.value = if (ok) "${spec.name} loaded into memory" else liteRtManager.state.value.lastError
    }

    fun runLiteRtAutoTuner() = viewModelScope.launch {
        _autoTunerOutput.value = liteRtManager.runAutoTuner()
    }

    fun toggleWakeWord(enabled: Boolean) = viewModelScope.launch {
        if (enabled) {
            val started = WakeWordService.start(app)
            prefs.updateSettings { it.copy(wakeWordEnabled = started) }
            if (!started) {
                _toastBanner.value = "Grant Microphone permission in Permissions before enabling Wake Word."
            }
        } else {
            prefs.updateSettings { it.copy(wakeWordEnabled = false) }
            WakeWordService.stop(app)
        }
    }

    fun toggleContinuousConversation(enabled: Boolean) = viewModelScope.launch {
        prefs.updateSettings { it.copy(continuousConversationEnabled = enabled) }
    }

    fun toggleFloatingBubble(enabled: Boolean) = viewModelScope.launch {
        if (enabled && !Settings.canDrawOverlays(app)) {
            prefs.updateSettings { it.copy(floatingBubbleEnabled = false) }
            _toastBanner.value = "Grant Display Over Other Apps permission first."
            return@launch
        }
        prefs.updateSettings { it.copy(floatingBubbleEnabled = enabled) }
        if (enabled) FloatingBubbleService.start(app) else FloatingBubbleService.stop(app)
    }

    fun toggleHaptics(enabled: Boolean) = viewModelScope.launch {
        prefs.updateSettings { it.copy(hapticFeedbackEnabled = enabled) }
    }

    fun toggleRealtimeWs(enabled: Boolean) = viewModelScope.launch {
        prefs.updateSettings { it.copy(realtimeWebSocketEnabled = enabled) }
    }

    fun toggleGeminiLiveVoice(enabled: Boolean) = viewModelScope.launch {
        prefs.updateSettings { it.copy(geminiLiveVoiceEnabled = enabled) }
    }

    fun setGeminiLiveModel(modelId: String) = viewModelScope.launch {
        val clean = modelId.trim().removePrefix("models/")
        if (clean.isNotEmpty()) {
            prefs.updateSettings { it.copy(geminiLiveModel = clean) }
        }
    }

    fun setGeminiLiveFallbackModel(modelId: String) = viewModelScope.launch {
        val clean = modelId.trim().removePrefix("models/")
        if (clean.isNotEmpty()) {
            prefs.updateSettings { it.copy(geminiLiveFallbackModel = clean) }
        }
    }

    fun setGeminiLiveCustomModel(modelId: String) = viewModelScope.launch {
        val clean = modelId.trim().removePrefix("models/")
        prefs.updateSettings { it.copy(geminiLiveCustomModel = clean) }
        _toastBanner.value = if (clean.isEmpty()) "Custom Live model cleared" else "Custom Live model saved: $clean"
    }

    fun refreshAvailableLiveModels() = viewModelScope.launch {
        voiceCoordinator.refreshAvailableLiveModels()
    }

    fun toggleWebhookMode(enabled: Boolean) = viewModelScope.launch {
        prefs.updateSettings { it.copy(webhookModeEnabled = enabled) }
    }

    fun toggleCallAssistant(enabled: Boolean) = viewModelScope.launch {
        prefs.updateSettings { it.copy(callAssistantEnabled = enabled) }
        if (enabled) callAssistant.startMonitoringIfPermitted()
    }

    fun updateCallAssistantConfig(
        enabled: Boolean,
        autoAnswer: Boolean,
        delaySec: Int,
        vipCsv: String,
        callLanguage: VoiceLanguage,
        receptionistPrompt: String,
        voipEnabled: Boolean,
        voipUri: String
    ) = viewModelScope.launch {
        prefs.updateSettings {
            it.copy(
                callAssistantEnabled = enabled,
                callAutoAnswerEnabled = autoAnswer,
                callAutoAnswerDelaySec = delaySec.coerceIn(2, 15),
                vipContactsCsv = vipCsv,
                callLanguage = callLanguage,
                callReceptionistPrompt = receptionistPrompt,
                voipSipModeEnabled = voipEnabled,
                voipSipServerUri = voipUri.trim()
            )
        }
        if (enabled) callAssistant.startMonitoringIfPermitted()
        _toastBanner.value = "Call Assistant configuration saved"
    }

    fun toggleAutoReplySms(enabled: Boolean, text: String) = viewModelScope.launch {
        prefs.updateSettings { it.copy(autoReplySmsEnabled = enabled, autoReplySmsText = text) }
    }

    fun updatePersona(persona: String) = viewModelScope.launch {
        prefs.updateSettings { it.copy(personaPrompt = persona) }
        _toastBanner.value = "Persona updated"
    }

    fun updateDisplayName(name: String) = viewModelScope.launch {
        prefs.updateSettings { it.copy(userDisplayName = name) }
    }

    fun addMemory(tier: MemoryTier, title: String, content: String, priority: Int = 6) = viewModelScope.launch {
        memoryDao.insert(MemoryEntity(tier = tier.name, title = title, content = content, priority = priority))
    }

    fun forgetMemory(id: Long) = viewModelScope.launch { memoryDao.forgetById(id) }
    fun clearMemoryTier(tier: MemoryTier) = viewModelScope.launch { memoryDao.clearTier(tier.name) }

    fun createRagProject(name: String, desc: String) = viewModelScope.launch {
        val id = projectRagDao.insertProject(ProjectEntity(name = name, description = desc))
        _selectedProjectId.value = id
    }

    fun ingestRagDocument(projectId: Long, uri: Uri, name: String) = viewModelScope.launch {
        val res = ragEngine.ingestDocument(projectId, uri, name)
        _toastBanner.value = res.fold(
            onSuccess = { chunks -> "Indexed $chunks chunks from $name" },
            onFailure = { err -> err.message ?: "Failed to index document" }
        )
    }

    fun saveSkill(skill: SkillEntity) = viewModelScope.launch { skillDao.insert(skill) }
    fun toggleSkill(skill: SkillEntity, enabled: Boolean) = viewModelScope.launch {
        skillDao.update(skill.copy(enabled = enabled, updatedAt = System.currentTimeMillis()))
    }
    fun deleteSkill(id: Long) = viewModelScope.launch { skillDao.deleteById(id) }

    fun addTrigger(trigger: TriggerEntity) = viewModelScope.launch { triggerDao.insert(trigger) }
    fun toggleTrigger(id: Long, enabled: Boolean) = viewModelScope.launch { triggerDao.setEnabled(id, enabled) }
    fun deleteTrigger(id: Long) = viewModelScope.launch { triggerDao.deleteById(id) }

    fun saveWorkflow(wf: WorkflowEntity) = viewModelScope.launch { workflowDao.insert(wf) }
    fun deleteWorkflow(id: Long) = viewModelScope.launch { workflowDao.deleteById(id) }
    fun runWorkflow(wf: WorkflowEntity) = viewModelScope.launch {
        val out = agentLoop.executeWorkflowOrFlow(wf)
        workflowDao.update(wf.copy(lastRunAt = System.currentTimeMillis(), runCount = wf.runCount + 1))
        _toastBanner.value = out
    }

    fun runAgentMission(goal: String) = viewModelScope.launch {
        val out = agentLoop.tryExecuteCommand(goal) ?: agentLoop.runGuiLoop(goal)
        _toastBanner.value = out
    }

    fun stopRecordingAndSaveFlow(name: String) = viewModelScope.launch {
        val yaml = agentLoop.stopRecordingAsYaml(name)
        workflowDao.insert(
            WorkflowEntity(
                name = name,
                description = "Recorded instant-replay Flow (no LLM required)",
                kind = "FLOW_YAML",
                definitionText = yaml
            )
        )
        _toastBanner.value = "Saved Flow '$name'"
    }

    fun clearActionLogs() = viewModelScope.launch { actionLogDao.clearAll() }

    fun toggleEmergencyStop() {
        if (safetyPolicy.policy.value.emergencyStopped) {
            safetyPolicy.clearEmergencyStop()
            _toastBanner.value = "Safety STOP cleared"
        } else {
            safetyPolicy.triggerEmergencyStop()
            voiceCoordinator.stopAll()
            liteRtManager.cancelActiveGeneration()
            _toastBanner.value = "EMERGENCY STOP TRIGGERED"
        }
    }

    suspend fun exportBackupJson(): String = backupManager.exportAllToJson()
    fun importBackupJson(raw: String) = viewModelScope.launch {
        val res = backupManager.importFromJson(raw)
        _toastBanner.value = res.fold(
            onSuccess = { count -> "Imported $count items successfully" },
            onFailure = { "Invalid backup JSON: ${it.message}" }
        )
    }
}
