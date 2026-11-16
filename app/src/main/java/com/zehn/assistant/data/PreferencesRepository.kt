package com.zehn.assistant.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zehn.assistant.data.model.AiExecutionMode
import com.zehn.assistant.data.model.AiProvider
import com.zehn.assistant.data.model.LiteRtBackend
import com.zehn.assistant.data.model.SttEngineType
import com.zehn.assistant.data.model.TtsEngineType
import com.zehn.assistant.data.model.VadEngineType
import com.zehn.assistant.data.model.VoiceLanguage
import com.zehn.assistant.data.model.VoiceProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

private val Context.zehnDataStore by preferencesDataStore(name = "zehn_preferences")

@Serializable
data class ZehnSettings(
    val onboardingCompleted: Boolean = false,
    val executionMode: AiExecutionMode = AiExecutionMode.HYBRID,
    val activeProvider: AiProvider = AiProvider.GEMINI,
    val activeModel: String = "gemini-2.5-flash",
    val fallbackChain: List<AiProvider> = listOf(
        AiProvider.GEMINI,
        AiProvider.GROQ,
        AiProvider.OPENROUTER,
        AiProvider.HUGGINGFACE,
        AiProvider.OPENAI_COMPATIBLE,
        AiProvider.OLLAMA,
        AiProvider.ON_DEVICE_LITERT
    ),
    val personaPrompt: String = "You are Zehn AI, a sharp, honest, multilingual (English, Urdu, Roman Urdu, Hindi, Hinglish) personal assistant and Android phone automation agent. Respect user safety rules, never fabricate action results, and cite document sources when answering from RAG projects.",
    val voiceLanguage: VoiceLanguage = VoiceLanguage.AUTO,
    val activeVoice: VoiceProfile = VoiceProfile.ARIA,
    val voiceRate: Float = 1.0f,
    val voicePitch: Float = 1.0f,
    val clonedVoiceReferenceUri: String = "",
    val vadEngine: VadEngineType = VadEngineType.SILERO_NEURAL,
    val sttEngine: SttEngineType = SttEngineType.ANDROID_SPEECH,
    val ttsEngine: TtsEngineType = TtsEngineType.ANDROID_STREAMING,
    val wakeWordEnabled: Boolean = false,
    val continuousConversationEnabled: Boolean = false,
    val realtimeWebSocketEnabled: Boolean = false,
    val webhookModeEnabled: Boolean = false,
    val callAssistantEnabled: Boolean = false,
    val callAutoAnswerEnabled: Boolean = false,
    val callAutoAnswerDelaySec: Int = 5,
    val vipContactsCsv: String = "",
    val callLanguage: VoiceLanguage = VoiceLanguage.AUTO,
    val callReceptionistPrompt: String = "Assalam-o-Alaikum, I'm Zehn AI, the owner's assistant. They are unavailable right now — how can I take a message?",
    val voipSipModeEnabled: Boolean = false,
    val voipSipServerUri: String = "",
    val autoReplySmsEnabled: Boolean = false,
    val autoReplySmsText: String = "I'm currently busy — Zehn AI will let me know you called.",
    val floatingBubbleEnabled: Boolean = false,
    val hapticFeedbackEnabled: Boolean = true,
    val liteRtBackend: LiteRtBackend = LiteRtBackend.AUTO,
    val speculativeDecodingEnabled: Boolean = true,
    val autoTunerEnabled: Boolean = true,
    val selectedLiteRtModelId: String = "gemma-4-e2b-q4",
    val samsungBatteryGuideDismissed: Boolean = false,
    val userDisplayName: String = "Zehn Explorer",
    val geminiLiveVoiceEnabled: Boolean = true,
    val geminiLiveModel: String = "gemini-3.1-flash-live-preview",
    val geminiLiveFallbackModel: String = "gemini-2.5-flash-native-audio-preview-12-2025",
    val geminiLiveCustomModel: String = "",
    // V4.0 Autonomous GitHub Research, Self-Upgrade & APK Build Engine settings
    val githubRepoSlug: String = "",
    val githubBaseBranch: String = "main",
    val autoDevEnabled: Boolean = false,
    val autoMergeOnGreen: Boolean = false,
    val allowWorkflowEdits: Boolean = false,
    val researchScheduleMode: String = "MANUAL", // MANUAL, DAILY, WEEKLY
    val researchFocusMode: String = "ALL_AI_TOOLS", // ALL_AI_TOOLS, SECURITY_ONLY
    val researchPaused: Boolean = false,
    val notifyOnUpdateReady: Boolean = true,
    val notifyOnBuildFailure: Boolean = false,
    val lastResearchTimestamp: Long = 0L,
    val nextScheduledResearchTimestamp: Long = 0L,
    val lastGoodCommitSha: String = "",
    val lastGoodTagOrVersion: String = "4.0.0"
)

@Singleton
class PreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val ONBOARDING = booleanPreferencesKey("onboarding_completed")
        val MODE = stringPreferencesKey("execution_mode")
        val PROVIDER = stringPreferencesKey("active_provider")
        val MODEL = stringPreferencesKey("active_model")
        val FALLBACK_CHAIN = stringPreferencesKey("fallback_chain")
        val PERSONA = stringPreferencesKey("persona_prompt")
        val LANGUAGE = stringPreferencesKey("voice_language")
        val VOICE = stringPreferencesKey("active_voice")
        val VOICE_RATE = floatPreferencesKey("voice_rate")
        val VOICE_PITCH = floatPreferencesKey("voice_pitch")
        val CLONED_URI = stringPreferencesKey("cloned_voice_uri")
        val VAD = stringPreferencesKey("vad_engine")
        val STT = stringPreferencesKey("stt_engine")
        val TTS = stringPreferencesKey("tts_engine")
        val WAKE_WORD = booleanPreferencesKey("wake_word_enabled")
        val CONTINUOUS_CONVERSATION = booleanPreferencesKey("continuous_conversation_enabled")
        val REALTIME_WS = booleanPreferencesKey("realtime_ws_enabled")
        val WEBHOOK_MODE = booleanPreferencesKey("webhook_mode_enabled")
        val CALL_ASSISTANT = booleanPreferencesKey("call_assistant_enabled")
        val CALL_AUTO_ANSWER = booleanPreferencesKey("call_auto_answer_enabled")
        val CALL_AUTO_DELAY = intPreferencesKey("call_auto_answer_delay_sec")
        val VIP_CONTACTS = stringPreferencesKey("vip_contacts_csv")
        val CALL_LANGUAGE = stringPreferencesKey("call_language")
        val CALL_RECEPTIONIST_PROMPT = stringPreferencesKey("call_receptionist_prompt")
        val VOIP_SIP_ENABLED = booleanPreferencesKey("voip_sip_mode_enabled")
        val VOIP_SIP_URI = stringPreferencesKey("voip_sip_server_uri")
        val AUTO_REPLY_SMS = booleanPreferencesKey("auto_reply_sms_enabled")
        val AUTO_REPLY_TEXT = stringPreferencesKey("auto_reply_sms_text")
        val FLOATING_BUBBLE = booleanPreferencesKey("floating_bubble_enabled")
        val HAPTICS = booleanPreferencesKey("haptics_enabled")
        val LITERT_BACKEND = stringPreferencesKey("litert_backend")
        val SPECULATIVE = booleanPreferencesKey("speculative_decoding")
        val AUTO_TUNER = booleanPreferencesKey("auto_tuner")
        val LITERT_MODEL = stringPreferencesKey("selected_litert_model")
        val SAMSUNG_GUIDE = booleanPreferencesKey("samsung_guide_dismissed")
        val USER_NAME = stringPreferencesKey("user_display_name")
        val GEMINI_LIVE_ENABLED = booleanPreferencesKey("gemini_live_voice_enabled")
        val GEMINI_LIVE_MODEL = stringPreferencesKey("gemini_live_model")
        val GEMINI_LIVE_FALLBACK = stringPreferencesKey("gemini_live_fallback_model")
        val GEMINI_LIVE_CUSTOM = stringPreferencesKey("gemini_live_custom_model")
        val GH_REPO = stringPreferencesKey("github_repo_slug")
        val GH_BASE_BRANCH = stringPreferencesKey("github_base_branch")
        val AUTO_DEV_ENABLED = booleanPreferencesKey("auto_dev_enabled")
        val AUTO_MERGE_GREEN = booleanPreferencesKey("auto_merge_on_green")
        val ALLOW_WORKFLOW_EDITS = booleanPreferencesKey("allow_workflow_edits")
        val RESEARCH_SCHEDULE = stringPreferencesKey("research_schedule_mode")
        val RESEARCH_FOCUS = stringPreferencesKey("research_focus_mode")
        val RESEARCH_PAUSED = booleanPreferencesKey("research_paused")
        val NOTIFY_UPDATE_READY = booleanPreferencesKey("notify_update_ready")
        val NOTIFY_BUILD_FAILURE = booleanPreferencesKey("notify_build_failure")
        val LAST_RESEARCH_TS = longPreferencesKey("last_research_ts")
        val NEXT_RESEARCH_TS = longPreferencesKey("next_research_ts")
        val LAST_GOOD_SHA = stringPreferencesKey("last_good_commit_sha")
        val LAST_GOOD_VER = stringPreferencesKey("last_good_tag_or_version")
    }

    val settings: Flow<ZehnSettings> = context.zehnDataStore.data.map { p ->
        val fallbackNames = p[Keys.FALLBACK_CHAIN]?.split(",")?.mapNotNull { name ->
            AiProvider.entries.firstOrNull { it.name == name }
        }
        val defaults = ZehnSettings()
        ZehnSettings(
            onboardingCompleted = p[Keys.ONBOARDING] ?: false,
            executionMode = enumOrDefault(p[Keys.MODE], AiExecutionMode.HYBRID),
            activeProvider = enumOrDefault(p[Keys.PROVIDER], AiProvider.GEMINI),
            activeModel = p[Keys.MODEL] ?: defaults.activeModel,
            fallbackChain = if (fallbackNames.isNullOrEmpty()) defaults.fallbackChain else fallbackNames,
            personaPrompt = p[Keys.PERSONA] ?: defaults.personaPrompt,
            voiceLanguage = enumOrDefault(p[Keys.LANGUAGE], VoiceLanguage.AUTO),
            activeVoice = enumOrDefault(p[Keys.VOICE], VoiceProfile.ARIA),
            voiceRate = p[Keys.VOICE_RATE] ?: 1.0f,
            voicePitch = p[Keys.VOICE_PITCH] ?: 1.0f,
            clonedVoiceReferenceUri = p[Keys.CLONED_URI] ?: "",
            vadEngine = enumOrDefault(p[Keys.VAD], VadEngineType.SILERO_NEURAL),
            sttEngine = enumOrDefault(p[Keys.STT], SttEngineType.ANDROID_SPEECH),
            ttsEngine = enumOrDefault(p[Keys.TTS], TtsEngineType.ANDROID_STREAMING),
            wakeWordEnabled = p[Keys.WAKE_WORD] ?: false,
            continuousConversationEnabled = p[Keys.CONTINUOUS_CONVERSATION] ?: false,
            realtimeWebSocketEnabled = p[Keys.REALTIME_WS] ?: false,
            webhookModeEnabled = p[Keys.WEBHOOK_MODE] ?: false,
            callAssistantEnabled = p[Keys.CALL_ASSISTANT] ?: false,
            callAutoAnswerEnabled = p[Keys.CALL_AUTO_ANSWER] ?: false,
            callAutoAnswerDelaySec = (p[Keys.CALL_AUTO_DELAY] ?: 5).coerceIn(2, 15),
            vipContactsCsv = p[Keys.VIP_CONTACTS] ?: "",
            callLanguage = enumOrDefault(p[Keys.CALL_LANGUAGE], VoiceLanguage.AUTO),
            callReceptionistPrompt = p[Keys.CALL_RECEPTIONIST_PROMPT] ?: defaults.callReceptionistPrompt,
            voipSipModeEnabled = p[Keys.VOIP_SIP_ENABLED] ?: false,
            voipSipServerUri = p[Keys.VOIP_SIP_URI] ?: "",
            autoReplySmsEnabled = p[Keys.AUTO_REPLY_SMS] ?: false,
            autoReplySmsText = p[Keys.AUTO_REPLY_TEXT] ?: defaults.autoReplySmsText,
            floatingBubbleEnabled = p[Keys.FLOATING_BUBBLE] ?: false,
            hapticFeedbackEnabled = p[Keys.HAPTICS] ?: true,
            liteRtBackend = enumOrDefault(p[Keys.LITERT_BACKEND], LiteRtBackend.AUTO),
            speculativeDecodingEnabled = p[Keys.SPECULATIVE] ?: true,
            autoTunerEnabled = p[Keys.AUTO_TUNER] ?: true,
            selectedLiteRtModelId = p[Keys.LITERT_MODEL] ?: defaults.selectedLiteRtModelId,
            samsungBatteryGuideDismissed = p[Keys.SAMSUNG_GUIDE] ?: false,
            userDisplayName = p[Keys.USER_NAME] ?: defaults.userDisplayName,
            geminiLiveVoiceEnabled = p[Keys.GEMINI_LIVE_ENABLED] ?: true,
            geminiLiveModel = p[Keys.GEMINI_LIVE_MODEL] ?: defaults.geminiLiveModel,
            geminiLiveFallbackModel = p[Keys.GEMINI_LIVE_FALLBACK] ?: defaults.geminiLiveFallbackModel,
            geminiLiveCustomModel = p[Keys.GEMINI_LIVE_CUSTOM] ?: "",
            githubRepoSlug = p[Keys.GH_REPO] ?: "",
            githubBaseBranch = p[Keys.GH_BASE_BRANCH]?.ifBlank { "main" } ?: "main",
            autoDevEnabled = p[Keys.AUTO_DEV_ENABLED] ?: false,
            autoMergeOnGreen = p[Keys.AUTO_MERGE_GREEN] ?: false,
            allowWorkflowEdits = p[Keys.ALLOW_WORKFLOW_EDITS] ?: false,
            researchScheduleMode = p[Keys.RESEARCH_SCHEDULE] ?: defaults.researchScheduleMode,
            researchFocusMode = p[Keys.RESEARCH_FOCUS] ?: defaults.researchFocusMode,
            researchPaused = p[Keys.RESEARCH_PAUSED] ?: false,
            notifyOnUpdateReady = p[Keys.NOTIFY_UPDATE_READY] ?: true,
            notifyOnBuildFailure = p[Keys.NOTIFY_BUILD_FAILURE] ?: false,
            lastResearchTimestamp = p[Keys.LAST_RESEARCH_TS] ?: 0L,
            nextScheduledResearchTimestamp = p[Keys.NEXT_RESEARCH_TS] ?: 0L,
            lastGoodCommitSha = p[Keys.LAST_GOOD_SHA] ?: "",
            lastGoodTagOrVersion = p[Keys.LAST_GOOD_VER] ?: defaults.lastGoodTagOrVersion
        )
    }

    suspend fun updateSettings(transform: (ZehnSettings) -> ZehnSettings) {
        context.zehnDataStore.edit { p ->
            val fallbackNames = p[Keys.FALLBACK_CHAIN]?.split(",")?.mapNotNull { name ->
                AiProvider.entries.firstOrNull { it.name == name }
            }
            val defaults = ZehnSettings()
            val current = ZehnSettings(
                onboardingCompleted = p[Keys.ONBOARDING] ?: false,
                executionMode = enumOrDefault(p[Keys.MODE], AiExecutionMode.HYBRID),
                activeProvider = enumOrDefault(p[Keys.PROVIDER], AiProvider.GEMINI),
                activeModel = p[Keys.MODEL] ?: defaults.activeModel,
                fallbackChain = if (fallbackNames.isNullOrEmpty()) defaults.fallbackChain else fallbackNames,
                personaPrompt = p[Keys.PERSONA] ?: defaults.personaPrompt,
                voiceLanguage = enumOrDefault(p[Keys.LANGUAGE], VoiceLanguage.AUTO),
                activeVoice = enumOrDefault(p[Keys.VOICE], VoiceProfile.ARIA),
                voiceRate = p[Keys.VOICE_RATE] ?: 1.0f,
                voicePitch = p[Keys.VOICE_PITCH] ?: 1.0f,
                clonedVoiceReferenceUri = p[Keys.CLONED_URI] ?: "",
                vadEngine = enumOrDefault(p[Keys.VAD], VadEngineType.SILERO_NEURAL),
                sttEngine = enumOrDefault(p[Keys.STT], SttEngineType.ANDROID_SPEECH),
                ttsEngine = enumOrDefault(p[Keys.TTS], TtsEngineType.ANDROID_STREAMING),
                wakeWordEnabled = p[Keys.WAKE_WORD] ?: false,
                continuousConversationEnabled = p[Keys.CONTINUOUS_CONVERSATION] ?: false,
                realtimeWebSocketEnabled = p[Keys.REALTIME_WS] ?: false,
                webhookModeEnabled = p[Keys.WEBHOOK_MODE] ?: false,
                callAssistantEnabled = p[Keys.CALL_ASSISTANT] ?: false,
                callAutoAnswerEnabled = p[Keys.CALL_AUTO_ANSWER] ?: false,
                callAutoAnswerDelaySec = (p[Keys.CALL_AUTO_DELAY] ?: 5).coerceIn(2, 15),
                vipContactsCsv = p[Keys.VIP_CONTACTS] ?: "",
                callLanguage = enumOrDefault(p[Keys.CALL_LANGUAGE], VoiceLanguage.AUTO),
                callReceptionistPrompt = p[Keys.CALL_RECEPTIONIST_PROMPT] ?: defaults.callReceptionistPrompt,
                voipSipModeEnabled = p[Keys.VOIP_SIP_ENABLED] ?: false,
                voipSipServerUri = p[Keys.VOIP_SIP_URI] ?: "",
                autoReplySmsEnabled = p[Keys.AUTO_REPLY_SMS] ?: false,
                autoReplySmsText = p[Keys.AUTO_REPLY_TEXT] ?: defaults.autoReplySmsText,
                floatingBubbleEnabled = p[Keys.FLOATING_BUBBLE] ?: false,
                hapticFeedbackEnabled = p[Keys.HAPTICS] ?: true,
                liteRtBackend = enumOrDefault(p[Keys.LITERT_BACKEND], LiteRtBackend.AUTO),
                speculativeDecodingEnabled = p[Keys.SPECULATIVE] ?: true,
                autoTunerEnabled = p[Keys.AUTO_TUNER] ?: true,
                selectedLiteRtModelId = p[Keys.LITERT_MODEL] ?: defaults.selectedLiteRtModelId,
                samsungBatteryGuideDismissed = p[Keys.SAMSUNG_GUIDE] ?: false,
                userDisplayName = p[Keys.USER_NAME] ?: defaults.userDisplayName,
                geminiLiveVoiceEnabled = p[Keys.GEMINI_LIVE_ENABLED] ?: true,
                geminiLiveModel = p[Keys.GEMINI_LIVE_MODEL] ?: defaults.geminiLiveModel,
                geminiLiveFallbackModel = p[Keys.GEMINI_LIVE_FALLBACK] ?: defaults.geminiLiveFallbackModel,
                geminiLiveCustomModel = p[Keys.GEMINI_LIVE_CUSTOM] ?: "",
                githubRepoSlug = p[Keys.GH_REPO] ?: "",
                githubBaseBranch = p[Keys.GH_BASE_BRANCH]?.ifBlank { "main" } ?: "main",
                autoDevEnabled = p[Keys.AUTO_DEV_ENABLED] ?: false,
                autoMergeOnGreen = p[Keys.AUTO_MERGE_GREEN] ?: false,
                allowWorkflowEdits = p[Keys.ALLOW_WORKFLOW_EDITS] ?: false,
                researchScheduleMode = p[Keys.RESEARCH_SCHEDULE] ?: defaults.researchScheduleMode,
                researchFocusMode = p[Keys.RESEARCH_FOCUS] ?: defaults.researchFocusMode,
                researchPaused = p[Keys.RESEARCH_PAUSED] ?: false,
                notifyOnUpdateReady = p[Keys.NOTIFY_UPDATE_READY] ?: true,
                notifyOnBuildFailure = p[Keys.NOTIFY_BUILD_FAILURE] ?: false,
                lastResearchTimestamp = p[Keys.LAST_RESEARCH_TS] ?: 0L,
                nextScheduledResearchTimestamp = p[Keys.NEXT_RESEARCH_TS] ?: 0L,
                lastGoodCommitSha = p[Keys.LAST_GOOD_SHA] ?: "",
                lastGoodTagOrVersion = p[Keys.LAST_GOOD_VER] ?: defaults.lastGoodTagOrVersion
            )
            val next = transform(current)
            p[Keys.ONBOARDING] = next.onboardingCompleted
            p[Keys.MODE] = next.executionMode.name
            p[Keys.PROVIDER] = next.activeProvider.name
            p[Keys.MODEL] = next.activeModel
            p[Keys.FALLBACK_CHAIN] = next.fallbackChain.joinToString(",") { it.name }
            p[Keys.PERSONA] = next.personaPrompt
            p[Keys.LANGUAGE] = next.voiceLanguage.name
            p[Keys.VOICE] = next.activeVoice.name
            p[Keys.VOICE_RATE] = next.voiceRate
            p[Keys.VOICE_PITCH] = next.voicePitch
            p[Keys.CLONED_URI] = next.clonedVoiceReferenceUri
            p[Keys.VAD] = next.vadEngine.name
            p[Keys.STT] = next.sttEngine.name
            p[Keys.TTS] = next.ttsEngine.name
            p[Keys.WAKE_WORD] = next.wakeWordEnabled
            p[Keys.CONTINUOUS_CONVERSATION] = next.continuousConversationEnabled
            p[Keys.REALTIME_WS] = next.realtimeWebSocketEnabled
            p[Keys.WEBHOOK_MODE] = next.webhookModeEnabled
            p[Keys.CALL_ASSISTANT] = next.callAssistantEnabled
            p[Keys.CALL_AUTO_ANSWER] = next.callAutoAnswerEnabled
            p[Keys.CALL_AUTO_DELAY] = next.callAutoAnswerDelaySec.coerceIn(2, 15)
            p[Keys.VIP_CONTACTS] = next.vipContactsCsv
            p[Keys.CALL_LANGUAGE] = next.callLanguage.name
            p[Keys.CALL_RECEPTIONIST_PROMPT] = next.callReceptionistPrompt
            p[Keys.VOIP_SIP_ENABLED] = next.voipSipModeEnabled
            p[Keys.VOIP_SIP_URI] = next.voipSipServerUri
            p[Keys.AUTO_REPLY_SMS] = next.autoReplySmsEnabled
            p[Keys.AUTO_REPLY_TEXT] = next.autoReplySmsText
            p[Keys.FLOATING_BUBBLE] = next.floatingBubbleEnabled
            p[Keys.HAPTICS] = next.hapticFeedbackEnabled
            p[Keys.LITERT_BACKEND] = next.liteRtBackend.name
            p[Keys.SPECULATIVE] = next.speculativeDecodingEnabled
            p[Keys.AUTO_TUNER] = next.autoTunerEnabled
            p[Keys.LITERT_MODEL] = next.selectedLiteRtModelId
            p[Keys.SAMSUNG_GUIDE] = next.samsungBatteryGuideDismissed
            p[Keys.USER_NAME] = next.userDisplayName
            p[Keys.GEMINI_LIVE_ENABLED] = next.geminiLiveVoiceEnabled
            p[Keys.GEMINI_LIVE_MODEL] = next.geminiLiveModel
            p[Keys.GEMINI_LIVE_FALLBACK] = next.geminiLiveFallbackModel
            p[Keys.GEMINI_LIVE_CUSTOM] = next.geminiLiveCustomModel
            p[Keys.GH_REPO] = next.githubRepoSlug.trim()
            p[Keys.GH_BASE_BRANCH] = next.githubBaseBranch.trim().ifBlank { "main" }
            p[Keys.AUTO_DEV_ENABLED] = next.autoDevEnabled
            p[Keys.AUTO_MERGE_GREEN] = next.autoMergeOnGreen
            p[Keys.ALLOW_WORKFLOW_EDITS] = next.allowWorkflowEdits
            p[Keys.RESEARCH_SCHEDULE] = next.researchScheduleMode
            p[Keys.RESEARCH_FOCUS] = next.researchFocusMode
            p[Keys.RESEARCH_PAUSED] = next.researchPaused
            p[Keys.NOTIFY_UPDATE_READY] = next.notifyOnUpdateReady
            p[Keys.NOTIFY_BUILD_FAILURE] = next.notifyOnBuildFailure
            p[Keys.LAST_RESEARCH_TS] = next.lastResearchTimestamp
            p[Keys.NEXT_RESEARCH_TS] = next.nextScheduledResearchTimestamp
            p[Keys.LAST_GOOD_SHA] = next.lastGoodCommitSha
            p[Keys.LAST_GOOD_VER] = next.lastGoodTagOrVersion
        }
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, fallback: T): T {
        if (name == null) return fallback
        return enumValues<T>().firstOrNull { it.name == name } ?: fallback
    }
}
