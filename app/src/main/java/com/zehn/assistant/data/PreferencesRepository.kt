package com.zehn.assistant.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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
        AiProvider.OPENAI_COMPATIBLE,
        AiProvider.OLLAMA,
        AiProvider.ON_DEVICE_LITERT
    ),
    val personaPrompt: String = "You are Zehn AI, a sharp, honest, multilingual (English, Urdu, Hindi, Hinglish) personal assistant and Android phone automation agent. Respect user safety rules, never fabricate action results, and cite document sources when answering from RAG projects.",
    val voiceLanguage: VoiceLanguage = VoiceLanguage.AUTO,
    val activeVoice: VoiceProfile = VoiceProfile.ARIA,
    val voiceRate: Float = 1.0f,
    val voicePitch: Float = 1.0f,
    val clonedVoiceReferenceUri: String = "",
    val vadEngine: VadEngineType = VadEngineType.SILERO_NEURAL,
    val sttEngine: SttEngineType = SttEngineType.ANDROID_SPEECH,
    val ttsEngine: TtsEngineType = TtsEngineType.ANDROID_STREAMING,
    val wakeWordEnabled: Boolean = false,
    val realtimeWebSocketEnabled: Boolean = false,
    val webhookModeEnabled: Boolean = false,
    val callAssistantEnabled: Boolean = false,
    val autoReplySmsEnabled: Boolean = false,
    val autoReplySmsText: String = "I'm currently busy — Zehn AI will let me know you called.",
    val floatingBubbleEnabled: Boolean = false,
    val hapticFeedbackEnabled: Boolean = true,
    val liteRtBackend: LiteRtBackend = LiteRtBackend.AUTO,
    val speculativeDecodingEnabled: Boolean = true,
    val autoTunerEnabled: Boolean = true,
    val selectedLiteRtModelId: String = "gemma-4-e2b-q4",
    val samsungBatteryGuideDismissed: Boolean = false,
    val userDisplayName: String = "Zehn Explorer"
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
        val REALTIME_WS = booleanPreferencesKey("realtime_ws_enabled")
        val WEBHOOK_MODE = booleanPreferencesKey("webhook_mode_enabled")
        val CALL_ASSISTANT = booleanPreferencesKey("call_assistant_enabled")
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
    }

    val settings: Flow<ZehnSettings> = context.zehnDataStore.data.map { p ->
        val fallbackNames = p[Keys.FALLBACK_CHAIN]?.split(",")?.mapNotNull { name ->
            AiProvider.entries.firstOrNull { it.name == name }
        }
        ZehnSettings(
            onboardingCompleted = p[Keys.ONBOARDING] ?: false,
            executionMode = enumOrDefault(p[Keys.MODE], AiExecutionMode.HYBRID),
            activeProvider = enumOrDefault(p[Keys.PROVIDER], AiProvider.GEMINI),
            activeModel = p[Keys.MODEL] ?: "gemini-2.5-flash",
            fallbackChain = if (fallbackNames.isNullOrEmpty()) ZehnSettings().fallbackChain else fallbackNames,
            personaPrompt = p[Keys.PERSONA] ?: ZehnSettings().personaPrompt,
            voiceLanguage = enumOrDefault(p[Keys.LANGUAGE], VoiceLanguage.AUTO),
            activeVoice = enumOrDefault(p[Keys.VOICE], VoiceProfile.ARIA),
            voiceRate = p[Keys.VOICE_RATE] ?: 1.0f,
            voicePitch = p[Keys.VOICE_PITCH] ?: 1.0f,
            clonedVoiceReferenceUri = p[Keys.CLONED_URI] ?: "",
            vadEngine = enumOrDefault(p[Keys.VAD], VadEngineType.SILERO_NEURAL),
            sttEngine = enumOrDefault(p[Keys.STT], SttEngineType.ANDROID_SPEECH),
            ttsEngine = enumOrDefault(p[Keys.TTS], TtsEngineType.ANDROID_STREAMING),
            wakeWordEnabled = p[Keys.WAKE_WORD] ?: false,
            realtimeWebSocketEnabled = p[Keys.REALTIME_WS] ?: false,
            webhookModeEnabled = p[Keys.WEBHOOK_MODE] ?: false,
            callAssistantEnabled = p[Keys.CALL_ASSISTANT] ?: false,
            autoReplySmsEnabled = p[Keys.AUTO_REPLY_SMS] ?: false,
            autoReplySmsText = p[Keys.AUTO_REPLY_TEXT] ?: ZehnSettings().autoReplySmsText,
            floatingBubbleEnabled = p[Keys.FLOATING_BUBBLE] ?: false,
            hapticFeedbackEnabled = p[Keys.HAPTICS] ?: true,
            liteRtBackend = enumOrDefault(p[Keys.LITERT_BACKEND], LiteRtBackend.AUTO),
            speculativeDecodingEnabled = p[Keys.SPECULATIVE] ?: true,
            autoTunerEnabled = p[Keys.AUTO_TUNER] ?: true,
            selectedLiteRtModelId = p[Keys.LITERT_MODEL] ?: "gemma-4-e2b-q4",
            samsungBatteryGuideDismissed = p[Keys.SAMSUNG_GUIDE] ?: false,
            userDisplayName = p[Keys.USER_NAME] ?: "Zehn Explorer"
        )
    }

    suspend fun updateSettings(transform: (ZehnSettings) -> ZehnSettings) {
        context.zehnDataStore.edit { p ->
            val current = ZehnSettings(
                onboardingCompleted = p[Keys.ONBOARDING] ?: false,
                executionMode = enumOrDefault(p[Keys.MODE], AiExecutionMode.HYBRID),
                activeProvider = enumOrDefault(p[Keys.PROVIDER], AiProvider.GEMINI),
                activeModel = p[Keys.MODEL] ?: "gemini-2.5-flash",
                personaPrompt = p[Keys.PERSONA] ?: ZehnSettings().personaPrompt,
                voiceLanguage = enumOrDefault(p[Keys.LANGUAGE], VoiceLanguage.AUTO),
                activeVoice = enumOrDefault(p[Keys.VOICE], VoiceProfile.ARIA),
                voiceRate = p[Keys.VOICE_RATE] ?: 1.0f,
                voicePitch = p[Keys.VOICE_PITCH] ?: 1.0f,
                clonedVoiceReferenceUri = p[Keys.CLONED_URI] ?: "",
                vadEngine = enumOrDefault(p[Keys.VAD], VadEngineType.SILERO_NEURAL),
                sttEngine = enumOrDefault(p[Keys.STT], SttEngineType.ANDROID_SPEECH),
                ttsEngine = enumOrDefault(p[Keys.TTS], TtsEngineType.ANDROID_STREAMING),
                wakeWordEnabled = p[Keys.WAKE_WORD] ?: false,
                realtimeWebSocketEnabled = p[Keys.REALTIME_WS] ?: false,
                webhookModeEnabled = p[Keys.WEBHOOK_MODE] ?: false,
                callAssistantEnabled = p[Keys.CALL_ASSISTANT] ?: false,
                autoReplySmsEnabled = p[Keys.AUTO_REPLY_SMS] ?: false,
                autoReplySmsText = p[Keys.AUTO_REPLY_TEXT] ?: ZehnSettings().autoReplySmsText,
                floatingBubbleEnabled = p[Keys.FLOATING_BUBBLE] ?: false,
                hapticFeedbackEnabled = p[Keys.HAPTICS] ?: true,
                liteRtBackend = enumOrDefault(p[Keys.LITERT_BACKEND], LiteRtBackend.AUTO),
                speculativeDecodingEnabled = p[Keys.SPECULATIVE] ?: true,
                autoTunerEnabled = p[Keys.AUTO_TUNER] ?: true,
                selectedLiteRtModelId = p[Keys.LITERT_MODEL] ?: "gemma-4-e2b-q4",
                samsungBatteryGuideDismissed = p[Keys.SAMSUNG_GUIDE] ?: false,
                userDisplayName = p[Keys.USER_NAME] ?: "Zehn Explorer"
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
            p[Keys.REALTIME_WS] = next.realtimeWebSocketEnabled
            p[Keys.WEBHOOK_MODE] = next.webhookModeEnabled
            p[Keys.CALL_ASSISTANT] = next.callAssistantEnabled
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
        }
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, fallback: T): T {
        if (name == null) return fallback
        return enumValues<T>().firstOrNull { it.name == name } ?: fallback
    }
}
