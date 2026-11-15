package com.zehn.assistant.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zehn.assistant.agent.AgentLoop
import com.zehn.assistant.agent.AgentPlanState
import com.zehn.assistant.agent.CallAssistantManager
import com.zehn.assistant.agent.FloatingBubbleService
import com.zehn.assistant.agent.PhoneControlExecutor
import com.zehn.assistant.agent.PlanManager
import com.zehn.assistant.agent.ZehnAccessibilityService
import com.zehn.assistant.agent.ZehnNotificationListenerService
import com.zehn.assistant.ai.AiEngineRouter
import com.zehn.assistant.ai.CloudLlmClient
import com.zehn.assistant.ai.LiteRtModelSpec
import com.zehn.assistant.ai.LlmMessage
import com.zehn.assistant.ai.OnDeviceLiteRtManager
import com.zehn.assistant.ai.OnDeviceModelState
import com.zehn.assistant.ai.ProviderCatalog
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
import com.zehn.assistant.voice.VoicePipelineCoordinator
import com.zehn.assistant.voice.VoicePipelineUiState
import com.zehn.assistant.voice.WakeWordService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    init {
        viewModelScope.launch {
            val allConvs = conversationDao.getAllOnce()
            if (allConvs.isNotEmpty()) {
                _activeConversationId.value = allConvs.first().id
            }
        }
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

            // Record working memory scratchpad
            memoryDao.insert(
                MemoryEntity(
                    tier = MemoryTier.WORKING.name,
                    title = "Last User Query",
                    content = clean.take(180),
                    priority = 6
                )
            )

            // Try fast-path / agent command if no image attached
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
                    _chatBusy.value = false
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
            _streamingDraft.value = ""
            _chatBusy.value = false
        }
    }

    fun generateImageInChat(prompt: String) {
        if (prompt.isBlank()) return
        viewModelScope.launch {
            _chatBusy.value = true
            val convId = _activeConversationId.value
            messageDao.insert(MessageEntity(conversationId = convId, role = "user", content = "/imagine $prompt"))
            val res = cloudLlmClient.generateImage(prompt)
            res.onSuccess { path ->
                messageDao.insert(
                    MessageEntity(
                        conversationId = convId,
                        role = "assistant",
                        content = "Generated image for \"$prompt\":",
                        imageUri = Uri.fromFile(java.io.File(path)).toString(),
                        modelUsed = "Image Generator"
                    )
                )
            }.onFailure { e ->
                messageDao.insert(
                    MessageEntity(
                        conversationId = convId,
                        role = "assistant",
                        content = e.message ?: "Image generation failed",
                        modelUsed = "Safety / Image"
                    )
                )
            }
            _chatBusy.value = false
        }
    }

    // Settings mutations
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
        prefs.updateSettings { it.copy(wakeWordEnabled = enabled) }
        if (enabled) WakeWordService.start(app) else WakeWordService.stop(app)
    }

    fun toggleFloatingBubble(enabled: Boolean) = viewModelScope.launch {
        prefs.updateSettings { it.copy(floatingBubbleEnabled = enabled) }
        if (enabled) FloatingBubbleService.start(app) else FloatingBubbleService.stop(app)
    }

    fun toggleHaptics(enabled: Boolean) = viewModelScope.launch {
        prefs.updateSettings { it.copy(hapticFeedbackEnabled = enabled) }
    }

    fun toggleRealtimeWs(enabled: Boolean) = viewModelScope.launch {
        prefs.updateSettings { it.copy(realtimeWebSocketEnabled = enabled) }
    }

    fun toggleWebhookMode(enabled: Boolean) = viewModelScope.launch {
        prefs.updateSettings { it.copy(webhookModeEnabled = enabled) }
    }

    fun toggleCallAssistant(enabled: Boolean) = viewModelScope.launch {
        prefs.updateSettings { it.copy(callAssistantEnabled = enabled) }
        if (enabled) callAssistant.startMonitoringIfPermitted()
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

    // Memory & RAG
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

    // Skills, Workflows, Triggers
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
