package com.zehn.assistant.voice

import com.zehn.assistant.agent.AgentLoop
import com.zehn.assistant.ai.AiEngineRouter
import com.zehn.assistant.ai.LlmMessage
import com.zehn.assistant.data.PreferencesRepository
import com.zehn.assistant.data.model.OrbState
import com.zehn.assistant.data.model.VoiceLanguage
import com.zehn.assistant.data.model.VoiceProfile
import com.zehn.assistant.security.SafetyPolicyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class VoicePipelineUiState(
    val orbState: OrbState = OrbState.IDLE,
    val micLevel: Float = 0f,
    val liveTranscript: String = "",
    val lastReply: String = "",
    val detectedLanguage: VoiceLanguage = VoiceLanguage.ENGLISH,
    val statusBanner: String = "Tap the glowing orb or say \"Hey Zehn\""
)

@Singleton
class VoicePipelineCoordinator @Inject constructor(
    private val vad: SileroStyleVadDetector,
    private val stt: AndroidSttEngine,
    private val tts: SentenceStreamingTtsEngine,
    private val realtimeWs: RealtimeWebSocketClient,
    private val aiRouter: AiEngineRouter,
    private val agentLoop: AgentLoop,
    private val prefs: PreferencesRepository,
    private val safetyPolicy: SafetyPolicyManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeTurnJob: Job? = null

    private val _uiState = MutableStateFlow(VoicePipelineUiState())
    val uiState: StateFlow<VoicePipelineUiState> = _uiState.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    init {
        scope.launch {
            try {
                vad.micLevel.collect { level ->
                    _uiState.update { it.copy(micLevel = level) }
                    // Barge-in: if user speaks loudly while Zehn is speaking, interrupt immediately
                    if (_uiState.value.orbState == OrbState.SPEAKING && level > 0.62f) {
                        bargeIn()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errors.tryEmit(e.message ?: "Voice activity monitor failed")
            }
        }
        scope.launch {
            try {
                stt.partialTranscript.collect { partial ->
                    if (partial.isNotBlank()) {
                        _uiState.update { it.copy(liveTranscript = partial) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errors.tryEmit(e.message ?: "Speech transcript listener failed")
            }
        }
    }

    fun startVoiceTurn() {
        try {
            if (safetyPolicy.policy.value.emergencyStopped) {
                val msg = "Emergency STOP active — clear STOP first"
                _uiState.update { it.copy(statusBanner = msg) }
                _errors.tryEmit(msg)
                return
            }
            // Barge-in if currently speaking or thinking
            if (_uiState.value.orbState == OrbState.SPEAKING || _uiState.value.orbState == OrbState.THINKING) {
                bargeIn()
            }

            scope.launch {
                try {
                    val settings = prefs.settings.first()
                    _uiState.update {
                        it.copy(
                            orbState = OrbState.LISTENING,
                            liveTranscript = "",
                            statusBanner = "Listening (${settings.voiceLanguage.label})..."
                        )
                    }
                    vad.startMonitoring(settings.vadEngine)
                    stt.startListening(
                        language = settings.voiceLanguage,
                        engineType = settings.sttEngine,
                        onFinalResult = { transcript ->
                            runCatching { vad.stopMonitoring() }
                            processSpokenQuery(transcript)
                        },
                        onError = { err ->
                            runCatching { vad.stopMonitoring() }
                            _uiState.update {
                                it.copy(orbState = OrbState.IDLE, statusBanner = err)
                            }
                            _errors.tryEmit(err)
                        }
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    runCatching { vad.stopMonitoring() }
                    val msg = e.message ?: "Voice capture error"
                    _uiState.update { it.copy(orbState = OrbState.IDLE, statusBanner = msg) }
                    _errors.tryEmit(msg)
                }
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Unable to start voice pipeline"
            _uiState.update { it.copy(orbState = OrbState.IDLE, statusBanner = msg) }
            _errors.tryEmit(msg)
        }
    }

    fun bargeIn() {
        try {
            activeTurnJob?.cancel()
            tts.stop()
            stt.stopListening()
            _uiState.update { it.copy(orbState = OrbState.IDLE, statusBanner = "Interrupted (Barge-in)") }
        } catch (e: Exception) {
            val msg = e.message ?: "Voice interruption error"
            _uiState.update { it.copy(orbState = OrbState.IDLE, statusBanner = msg) }
            _errors.tryEmit(msg)
        }
    }

    fun stopAll() {
        try {
            activeTurnJob?.cancel()
            vad.stopMonitoring()
            stt.stopListening()
            tts.stop()
            realtimeWs.disconnect()
            _uiState.update { it.copy(orbState = OrbState.IDLE, statusBanner = "Stopped") }
        } catch (e: Exception) {
            val msg = e.message ?: "Failed to stop voice pipeline cleanly"
            _uiState.update { it.copy(orbState = OrbState.IDLE, statusBanner = msg) }
            _errors.tryEmit(msg)
        }
    }

    fun previewVoice(profile: VoiceProfile) {
        try {
            val sample = when (profile.localeTag) {
                "ur-PK" -> "Assalam-o-Alaikum, main ${profile.displayName} hoon — aapka Zehn voice assistant."
                "hi-IN" -> "Namaste, main ${profile.displayName} hoon — Zehn AI aapki seva mein hai."
                else -> "Hello, I'm ${profile.displayName}, ready to assist on Zehn AI."
            }
            tts.speakImmediate(sample, profile)
        } catch (e: Exception) {
            _errors.tryEmit(e.message ?: "Voice preview failed")
        }
    }

    fun processSpokenQuery(transcript: String) {
        activeTurnJob?.cancel()
        activeTurnJob = scope.launch {
            try {
                val settings = prefs.settings.first()
                val detected = LanguageDetector.detect(transcript, settings.voiceLanguage)
                _uiState.update {
                    it.copy(
                        orbState = OrbState.THINKING,
                        liveTranscript = transcript,
                        detectedLanguage = detected,
                        lastReply = "",
                        statusBanner = "Thinking (${detected.label})..."
                    )
                }

                tts.configure(settings.activeVoice, settings.voiceRate, settings.voicePitch, detected)

                // 1. Check Fast-Path / Agent Automation first
                val agentResult = agentLoop.tryExecuteCommand(transcript)
                if (agentResult != null) {
                    _uiState.update {
                        it.copy(
                            orbState = OrbState.SPEAKING,
                            lastReply = agentResult,
                            statusBanner = "Speaking agent result..."
                        )
                    }
                    tts.speakImmediate(agentResult, settings.activeVoice)
                    _uiState.update { it.copy(orbState = OrbState.IDLE, statusBanner = "Ready") }
                    return@launch
                }

                // 2. Realtime WebSocket path if toggled
                if (settings.realtimeWebSocketEnabled && realtimeWs.connect(settings.personaPrompt)) {
                    realtimeWs.sendUserTurn(transcript)
                    _uiState.update { it.copy(orbState = OrbState.SPEAKING, statusBanner = "Realtime WebSocket active") }
                    realtimeWs.events.collect { delta ->
                        if (delta == "[DONE]") {
                            tts.flushRemaining()
                            _uiState.update { it.copy(orbState = OrbState.IDLE, statusBanner = "Ready") }
                            return@collect
                        } else if (delta.startsWith("[ERROR]")) {
                            _uiState.update { it.copy(orbState = OrbState.IDLE, statusBanner = delta) }
                            _errors.tryEmit(delta)
                            return@collect
                        } else {
                            _uiState.update { it.copy(lastReply = it.lastReply + delta) }
                            tts.streamTokenChunk(delta)
                        }
                    }
                    return@launch
                }

                // 3. Standard Streaming AI Router -> Sentence-chunked TTS
                val replyBuilder = StringBuilder()
                var switchedToSpeaking = false
                aiRouter.routeAndStream(
                    history = listOf(LlmMessage("user", transcript)),
                    userInput = transcript
                ).collect { chunk ->
                    if (chunk.error != null) {
                        _uiState.update {
                            it.copy(
                                orbState = OrbState.IDLE,
                                lastReply = chunk.error,
                                statusBanner = chunk.error
                            )
                        }
                        _errors.tryEmit(chunk.error)
                    }
                    if (chunk.delta.isNotEmpty()) {
                        if (!switchedToSpeaking) {
                            switchedToSpeaking = true
                            _uiState.update { it.copy(orbState = OrbState.SPEAKING, statusBanner = "Speaking...") }
                        }
                        replyBuilder.append(chunk.delta)
                        _uiState.update { it.copy(lastReply = replyBuilder.toString()) }
                        tts.streamTokenChunk(chunk.delta)
                    }
                    if (chunk.completed) {
                        tts.flushRemaining()
                        _uiState.update { it.copy(orbState = OrbState.IDLE, statusBanner = "Ready (${chunk.modelUsed})") }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: "Voice assistant error"
                runCatching { vad.stopMonitoring() }
                runCatching { tts.stop() }
                _uiState.update { it.copy(orbState = OrbState.IDLE, statusBanner = msg) }
                _errors.tryEmit(msg)
            }
        }
    }
}
