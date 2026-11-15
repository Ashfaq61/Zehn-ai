package com.zehn.assistant.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zehn.assistant.ai.ProviderCatalog
import com.zehn.assistant.data.model.AiExecutionMode
import com.zehn.assistant.data.model.AiProvider
import com.zehn.assistant.data.model.LiteRtBackend
import com.zehn.assistant.data.model.SttEngineType
import com.zehn.assistant.data.model.TtsEngineType
import com.zehn.assistant.data.model.VadEngineType
import com.zehn.assistant.data.model.VoiceLanguage
import com.zehn.assistant.data.model.VoiceProfile
import com.zehn.assistant.security.KeystoreCryptoManager
import com.zehn.assistant.ui.ZehnViewModel
import com.zehn.assistant.ui.components.ClayActionButton
import com.zehn.assistant.ui.components.ClayGlassCard
import com.zehn.assistant.ui.components.ClayToggleSwitch
import com.zehn.assistant.ui.theme.ZehnColors

@Composable
fun VoiceAndAiModelsScreen(vm: ZehnViewModel) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val onDevice by vm.onDeviceState.collectAsStateWithLifecycle()
    val tunerOut by vm.autoTunerOutput.collectAsStateWithLifecycle()

    val audioClipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            vm.setClonedVoiceUri(uri.toString())
            vm.showBanner("Reference voice clip bound for cloned voice profile.")
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Voice & AI Models", style = MaterialTheme.typography.headlineMedium, color = ZehnColors.FrostWhite)
            Text("Modular VAD/STT/TTS, 8 voices, Multi-Provider Routing & LiteRT-LM Gemma 4", style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
        }

        // Execution Mode
        item {
            ClayGlassCard(accentGlow = true) {
                Text("AI Execution Mode", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiExecutionMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.executionMode == mode,
                            onClick = { vm.setExecutionMode(mode) },
                            label = { Text(mode.label) }
                        )
                    }
                }
                Text(settings.executionMode.description, style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
            }
        }

        // Modular Voice Pipeline
        item {
            ClayGlassCard {
                Text("Modular Voice Pipeline", style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                Spacer(Modifier.height(6.dp))
                Text("VAD Module", style = MaterialTheme.typography.labelMedium, color = ZehnColors.Cyan)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VadEngineType.entries.forEach { v ->
                        FilterChip(selected = settings.vadEngine == v, onClick = { vm.setPipelineEngines(v, settings.sttEngine, settings.ttsEngine) }, label = { Text(v.label) })
                    }
                }
                Text("STT Module", style = MaterialTheme.typography.labelMedium, color = ZehnColors.Cyan)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SttEngineType.entries.forEach { s ->
                        FilterChip(selected = settings.sttEngine == s, onClick = { vm.setPipelineEngines(settings.vadEngine, s, settings.ttsEngine) }, label = { Text(s.label) })
                    }
                }
                Text("Language Auto-Detect", style = MaterialTheme.typography.labelMedium, color = ZehnColors.Cyan)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VoiceLanguage.entries.forEach { lang ->
                        FilterChip(selected = settings.voiceLanguage == lang, onClick = { vm.setVoiceLanguage(lang) }, label = { Text(lang.label) })
                    }
                }
            }
        }

        // 8 Voices (4 Male, 4 Female) + Reference Clone
        item {
            ClayGlassCard {
                Text("8 Voices (4 Female / 4 Male) & Voice Cloning", style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                Spacer(Modifier.height(8.dp))
                VoiceProfile.entries.forEach { vp ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${vp.displayName} (${vp.gender}) • ${vp.localeTag}", style = MaterialTheme.typography.titleMedium, color = if (settings.activeVoice == vp) ZehnColors.Cyan else ZehnColors.FrostWhite)
                            Text(vp.tagline, style = MaterialTheme.typography.labelSmall, color = ZehnColors.SlateMuted)
                        }
                        Row {
                            IconButton(onClick = { vm.voiceCoordinator.previewVoice(vp) }) {
                                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Preview ${vp.displayName}", tint = ZehnColors.Cyan)
                            }
                            ClayActionButton(
                                text = if (settings.activeVoice == vp) "Active" else "Select",
                                onClick = { vm.setVoiceProfile(vp) }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                ClayActionButton(
                    text = if (settings.clonedVoiceReferenceUri.isBlank()) "Attach Short Reference Clip (Voice Clone)" else "Reference Clip Attached",
                    icon = Icons.Filled.AudioFile,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { audioClipPicker.launch(arrayOf("audio/*")) }
                )
            }
        }

        // Cloud & Local Provider Picker
        item {
            ClayGlassCard {
                Text("Cloud & Local Providers", style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiProvider.entries.forEach { prov ->
                        FilterChip(
                            selected = settings.activeProvider == prov,
                            onClick = {
                                val firstModel = ProviderCatalog.modelsFor(prov).firstOrNull()?.id ?: ""
                                vm.setProviderAndModel(prov, firstModel)
                            },
                            label = { Text(prov.displayName) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Models for ${settings.activeProvider.displayName}", style = MaterialTheme.typography.labelMedium, color = ZehnColors.Cyan)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProviderCatalog.modelsFor(settings.activeProvider).forEach { m ->
                        FilterChip(
                            selected = settings.activeModel == m.id,
                            onClick = { vm.setProviderAndModel(settings.activeProvider, m.id) },
                            label = { Text("${m.label} (${m.contextWindow})") }
                        )
                    }
                }
            }
        }

        // On-Device LiteRT-LM Gemma 4 + Hugging Face Downloader + Auto-Tuner
        item {
            ClayGlassCard(accentGlow = true) {
                Text("On-Device LiteRT-LM (Gemma 4 & Qwen3)", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
                Text(
                    "Device RAM: ${onDevice.totalRamMb} MB • Free Storage: ${onDevice.availableStorageMb} MB • Engine: ${onDevice.activeBackendLabel} (${"%.1f".format(onDevice.measuredTokensPerSec)} tok/s)",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZehnColors.SlateMuted
                )
                Spacer(Modifier.height(8.dp))

                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiteRtBackend.entries.forEach { b ->
                        FilterChip(selected = settings.liteRtBackend == b, onClick = { vm.setLiteRtBackend(b) }, label = { Text(b.label) })
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Speculative Decoding", color = ZehnColors.FrostWhite)
                    ClayToggleSwitch(checked = settings.speculativeDecodingEnabled, onCheckedChange = { vm.setSpeculativeDecoding(it) })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto-Tuner (GPU/CPU by speed)", color = ZehnColors.FrostWhite)
                    ClayToggleSwitch(checked = settings.autoTunerEnabled, onCheckedChange = { vm.setAutoTuner(it) })
                }
                ClayActionButton(text = "Run Hardware Auto-Tuner", icon = Icons.Filled.Speed, onClick = { vm.runLiteRtAutoTuner() })
                if (tunerOut.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(tunerOut, style = MaterialTheme.typography.bodyMedium, color = ZehnColors.Emerald)
                }
                onDevice.lastError?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = ZehnColors.RoseStop)
                }
            }
        }

        items(ProviderCatalog.liteRtModels, key = { it.id }) { spec ->
            val downloaded = spec.id in onDevice.downloadedModels
            val downloading = onDevice.downloadingModelId == spec.id
            val eligible = vm.liteRtManager.isModelEligibleForRam(spec)
            ClayGlassCard {
                Text("${spec.name} • ${spec.quantization}", style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                Text("${spec.repo} • Min RAM ${spec.minRamMb} MB", style = MaterialTheme.typography.labelSmall, color = ZehnColors.Cyan)
                Text(spec.description, style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
                if (!eligible) {
                    Text("Warning: Device RAM (${onDevice.totalRamMb} MB) below recommended ${spec.minRamMb} MB.", color = ZehnColors.Amber, style = MaterialTheme.typography.labelSmall)
                }
                if (downloading) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { onDevice.downloadProgress }, modifier = Modifier.fillMaxWidth(), color = ZehnColors.Cyan)
                    Text(onDevice.downloadStatusText, style = MaterialTheme.typography.labelSmall, color = ZehnColors.Cyan)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (downloading) {
                        ClayActionButton(text = "Pause", icon = Icons.Filled.Pause, isDanger = true, onClick = { vm.liteRtManager.pauseDownload() })
                    } else if (!downloaded) {
                        ClayActionButton(
                            text = "Download HF",
                            icon = Icons.Filled.CloudDownload,
                            enabled = eligible,
                            onClick = { vm.liteRtManager.startResumableDownload(spec) }
                        )
                    } else {
                        ClayActionButton(
                            text = if (onDevice.activeModelId == spec.id && onDevice.engineReady) "Loaded" else "Load Model",
                            icon = Icons.Filled.PlayArrow,
                            onClick = { vm.selectAndLoadLiteRtModel(spec) }
                        )
                        IconButton(onClick = { vm.liteRtManager.deleteModel(spec) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete model", tint = ZehnColors.RoseStop)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ApiKeysScreen(vm: ZehnViewModel) {
    val keysVersion by vm.keysVersion.collectAsStateWithLifecycle()

    var geminiPool by remember(keysVersion) { mutableStateOf(vm.crypto.getGeminiKeys().joinToString("\n")) }
    var openAiKey by remember(keysVersion) { mutableStateOf(vm.crypto.getSecret(KeystoreCryptoManager.KEY_OPENAI)) }
    var openAiUrl by remember(keysVersion) { mutableStateOf(vm.crypto.getSecret(KeystoreCryptoManager.KEY_OPENAI_URL)) }
    var anthropicKey by remember(keysVersion) { mutableStateOf(vm.crypto.getSecret(KeystoreCryptoManager.KEY_ANTHROPIC)) }
    var groqKey by remember(keysVersion) { mutableStateOf(vm.crypto.getSecret(KeystoreCryptoManager.KEY_GROQ)) }
    var openRouterKey by remember(keysVersion) { mutableStateOf(vm.crypto.getSecret(KeystoreCryptoManager.KEY_OPENROUTER)) }
    var togetherKey by remember(keysVersion) { mutableStateOf(vm.crypto.getSecret(KeystoreCryptoManager.KEY_TOGETHER)) }
    var cohereKey by remember(keysVersion) { mutableStateOf(vm.crypto.getSecret(KeystoreCryptoManager.KEY_COHERE)) }
    var ollamaUrl by remember(keysVersion) { mutableStateOf(vm.crypto.getSecret(KeystoreCryptoManager.KEY_OLLAMA_URL).ifBlank { "http://127.0.0.1:11434/v1/" }) }
    var webhookUrl by remember(keysVersion) { mutableStateOf(vm.crypto.getSecret(KeystoreCryptoManager.KEY_WEBHOOK_URL)) }
    var realtimeWsUrl by remember(keysVersion) { mutableStateOf(vm.crypto.getSecret(KeystoreCryptoManager.KEY_REALTIME_WS_URL)) }
    var realtimeWsKey by remember(keysVersion) { mutableStateOf(vm.crypto.getSecret(KeystoreCryptoManager.KEY_REALTIME_WS_KEY)) }
    var hfToken by remember(keysVersion) { mutableStateOf(vm.crypto.getSecret(KeystoreCryptoManager.KEY_HF_TOKEN)) }

    var revealKeys by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Encrypted API Keys", style = MaterialTheme.typography.headlineMedium, color = ZehnColors.FrostWhite)
                    Text("Android Keystore AES-256-GCM hardware-backed vault", style = MaterialTheme.typography.bodyMedium, color = ZehnColors.Cyan)
                }
                ClayToggleSwitch(checked = revealKeys, onCheckedChange = { revealKeys = it })
            }
        }

        item {
            ClayGlassCard(accentGlow = true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Security, contentDescription = null, tint = ZehnColors.Cyan)
                    Spacer(Modifier.width(8.dp))
                    Text("Gemini Key Pool (Auto-Rotation on 429 Quota)", style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = geminiPool,
                    onValueChange = { geminiPool = it },
                    label = { Text("One key per line for rotation") },
                    visualTransformation = if (revealKeys) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("api_gemini_pool"),
                    minLines = 2
                )
            }
        }

        item {
            ClayGlassCard {
                VaultField("OpenAI API Key", openAiKey, revealKeys) { openAiKey = it }
                VaultField("OpenAI-Compatible Base URL (Optional)", openAiUrl, true) { openAiUrl = it }
                VaultField("Anthropic Claude Key", anthropicKey, revealKeys) { anthropicKey = it }
                VaultField("Groq API Key", groqKey, revealKeys) { groqKey = it }
                VaultField("OpenRouter API Key", openRouterKey, revealKeys) { openRouterKey = it }
                VaultField("Together AI Key", togetherKey, revealKeys) { togetherKey = it }
                VaultField("Cohere API Key", cohereKey, revealKeys) { cohereKey = it }
                VaultField("Ollama Local Server URL", ollamaUrl, true) { ollamaUrl = it }
                VaultField("Webhook POST Backend URL", webhookUrl, true) { webhookUrl = it }
                VaultField("Realtime WebSocket URL", realtimeWsUrl, true) { realtimeWsUrl = it }
                VaultField("Realtime WebSocket Key", realtimeWsKey, revealKeys) { realtimeWsKey = it }
                VaultField("Hugging Face Token (Gated Models)", hfToken, revealKeys) { hfToken = it }

                Spacer(Modifier.height(12.dp))
                ClayActionButton(
                    text = "Encrypt & Save All Keys",
                    icon = Icons.Filled.Check,
                    modifier = Modifier.fillMaxWidth().testTag("save_api_keys_button"),
                    onClick = {
                        vm.crypto.saveGeminiKeys(geminiPool.lines())
                        vm.crypto.saveSecret(KeystoreCryptoManager.KEY_OPENAI, openAiKey)
                        vm.crypto.saveSecret(KeystoreCryptoManager.KEY_OPENAI_URL, openAiUrl)
                        vm.crypto.saveSecret(KeystoreCryptoManager.KEY_ANTHROPIC, anthropicKey)
                        vm.crypto.saveSecret(KeystoreCryptoManager.KEY_GROQ, groqKey)
                        vm.crypto.saveSecret(KeystoreCryptoManager.KEY_OPENROUTER, openRouterKey)
                        vm.crypto.saveSecret(KeystoreCryptoManager.KEY_TOGETHER, togetherKey)
                        vm.crypto.saveSecret(KeystoreCryptoManager.KEY_COHERE, cohereKey)
                        vm.crypto.saveSecret(KeystoreCryptoManager.KEY_OLLAMA_URL, ollamaUrl)
                        vm.crypto.saveSecret(KeystoreCryptoManager.KEY_WEBHOOK_URL, webhookUrl)
                        vm.crypto.saveSecret(KeystoreCryptoManager.KEY_REALTIME_WS_URL, realtimeWsUrl)
                        vm.crypto.saveSecret(KeystoreCryptoManager.KEY_REALTIME_WS_KEY, realtimeWsKey)
                        vm.crypto.saveSecret(KeystoreCryptoManager.KEY_HF_TOKEN, hfToken)
                        vm.showBanner("Keys encrypted with Android Keystore AES-256-GCM")
                    }
                )
            }
        }
    }
}

@Composable
private fun VaultField(
    label: String,
    value: String,
    reveal: Boolean,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
}
