package com.zehn.assistant.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zehn.assistant.data.model.OrbState
import com.zehn.assistant.ui.ZehnViewModel
import com.zehn.assistant.ui.components.ClayActionButton
import com.zehn.assistant.ui.components.ClayGlassCard
import com.zehn.assistant.ui.components.EmergencyStopPill
import com.zehn.assistant.ui.components.ZehnAnimatedOrb
import com.zehn.assistant.ui.theme.ZehnColors

@Composable
fun HomeScreen(
    vm: ZehnViewModel,
    onOpenOrb: () -> Unit,
    onOpenChatSheet: () -> Unit,
    onOpenMission: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenCamera: () -> Unit
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val policy by vm.policy.collectAsStateWithLifecycle()
    val voiceState by vm.voiceUiState.collectAsStateWithLifecycle()
    val telemetry by vm.systemStatusText.collectAsStateWithLifecycle()
    val planState by vm.agentPlanState.collectAsStateWithLifecycle()
    val triggers by vm.triggers.collectAsStateWithLifecycle()
    val crashReport by vm.lastCrashReport.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.voiceCoordinator.startVoiceTurn()
        } else {
            vm.showBanner("Microphone permission is required for voice input.")
        }
    }

    fun startMicWithPermission() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.voiceCoordinator.startVoiceTurn()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 126.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Zehn AI", style = MaterialTheme.typography.headlineMedium, color = ZehnColors.FrostWhite)
                    Text(
                        "${settings.executionMode.label} • ${settings.activeModel}",
                        style = MaterialTheme.typography.labelMedium,
                        color = ZehnColors.Cyan
                    )
                }
                EmergencyStopPill(
                    emergencyStopped = policy.emergencyStopped,
                    onToggleStop = { vm.toggleEmergencyStop() }
                )
            }
        }

        crashReport?.let { crash ->
            item {
                ClayGlassCard(
                    modifier = Modifier.fillMaxWidth().testTag("crash_recovery_card")
                ) {
                    Text(
                        "Recovered from Previous Error (${crash.exceptionType})",
                        style = MaterialTheme.typography.titleMedium,
                        color = ZehnColors.Amber
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        crash.sanitizedMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ZehnColors.SlateMuted
                    )
                    Spacer(Modifier.height(8.dp))
                    ClayActionButton(
                        text = "Dismiss Recovery Notice",
                        onClick = { vm.dismissCrashReport() },
                        modifier = Modifier.testTag("dismiss_crash_recovery")
                    )
                }
            }
        }

        // Centerpiece Orb Hero Card
        item {
            ClayGlassCard(
                accentGlow = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenOrb
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ZehnAnimatedOrb(
                        orbState = voiceState.orbState,
                        micLevel = voiceState.micLevel,
                        sizeDp = 164.dp,
                        onClick = {
                            if (voiceState.orbState == OrbState.IDLE) startMicWithPermission()
                            else vm.voiceCoordinator.bargeIn()
                        },
                        modifier = Modifier.testTag("home_orb_button")
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = voiceState.statusBanner,
                        style = MaterialTheme.typography.titleMedium,
                        color = ZehnColors.FrostWhite
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Tap Orb to Speak / Barge-in • Tap card for Immersive Orb",
                        style = MaterialTheme.typography.labelSmall,
                        color = ZehnColors.SlateMuted
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ClayActionButton(
                            text = "Quick Chat Sheet",
                            icon = Icons.Filled.ChatBubble,
                            onClick = onOpenChatSheet,
                            modifier = Modifier.testTag("home_open_chat_sheet")
                        )
                        ClayActionButton(
                            text = "Vision / OCR",
                            icon = Icons.Filled.CameraAlt,
                            onClick = onOpenCamera
                        )
                    }
                }
            }
        }

        // Quick Automation Chips
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickCommandChip("Flashlight", Icons.Filled.FlashlightOn) {
                    vm.showBanner(vm.phoneControl.toggleFlashlight())
                }
                QuickCommandChip("Agent Mission", Icons.Filled.Bolt, onClick = onOpenMission)
                QuickCommandChip("AI Models", Icons.Filled.Memory, onClick = onOpenModels)
                QuickCommandChip("Safety & Logs", Icons.Filled.Shield, onClick = onOpenSecurity)
                QuickCommandChip("WhatsApp / Call", Icons.Filled.PhoneInTalk) {
                    vm.sendChatMessage("Read notifications")
                }
            }
        }

        // Live Agent Plan Card (visible when running or completed recently)
        item {
            AnimatedVisibility(visible = planState.goal.isNotBlank()) {
                ClayGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(planState.path.label, style = MaterialTheme.typography.labelMedium, color = ZehnColors.Cyan)
                        Text("${(planState.confidence * 100).toInt()}% conf", style = MaterialTheme.typography.labelSmall, color = ZehnColors.SlateMuted)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(planState.goal, style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                    Spacer(Modifier.height(8.dp))
                    planState.steps.forEach { step ->
                        Row(
                            modifier = Modifier.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "[${step.status}]",
                                style = MaterialTheme.typography.labelSmall,
                                color = when (step.status) {
                                    "DONE" -> ZehnColors.Emerald
                                    "BLOCKED" -> ZehnColors.RoseStop
                                    else -> ZehnColors.Cyan
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(step.label, style = MaterialTheme.typography.bodyMedium, color = ZehnColors.FrostWhite)
                        }
                    }
                }
            }
        }

        // System Telemetry Card
        item {
            ClayGlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Device Telemetry", style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                    IconButton(onClick = { vm.refreshTelemetry() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh telemetry", tint = ZehnColors.Cyan)
                    }
                }
                Text(telemetry, style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
            }
        }

        // Active Triggers Snapshot
        item {
            val activeCount = triggers.count { it.enabled }
            ClayGlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Automations & Voice", style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                        Text(
                            "$activeCount active triggers • Wake word: ${if (settings.wakeWordEnabled) "ON" else "OFF"} • Voice: ${settings.activeVoice.displayName}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ZehnColors.SlateMuted
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = ZehnColors.Cyan)
                }
            }
        }
    }
}

@Composable
private fun QuickCommandChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, color = ZehnColors.FrostWhite) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = ZehnColors.Cyan, modifier = Modifier.size(18.dp)) },
        colors = AssistChipDefaults.assistChipColors(containerColor = ZehnColors.SurfaceElevated),
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun OrbScreen(
    vm: ZehnViewModel,
    onBack: () -> Unit
) {
    val voiceState by vm.voiceUiState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val policy by vm.policy.collectAsStateWithLifecycle()
    val planState by vm.agentPlanState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.voiceCoordinator.startVoiceTurn()
        } else {
            vm.showBanner("Microphone permission is required for voice input.")
        }
    }

    fun startMicWithPermission() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.voiceCoordinator.startVoiceTurn()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ZehnColors.Background)
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .padding(bottom = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Zehn Voice Orb", style = MaterialTheme.typography.titleLarge, color = ZehnColors.FrostWhite)
                Text(
                    "VAD: ${settings.vadEngine.label} • Lang: ${voiceState.detectedLanguage.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZehnColors.Cyan
                )
            }
            EmergencyStopPill(
                emergencyStopped = policy.emergencyStopped,
                onToggleStop = { vm.toggleEmergencyStop() }
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ZehnAnimatedOrb(
                orbState = voiceState.orbState,
                micLevel = voiceState.micLevel,
                sizeDp = 248.dp,
                onClick = {
                    if (voiceState.orbState == OrbState.IDLE) startMicWithPermission()
                    else vm.voiceCoordinator.bargeIn()
                },
                modifier = Modifier.testTag("immersive_orb_button")
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = voiceState.orbState.name,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = ZehnColors.Cyan
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { voiceState.micLevel.coerceIn(0.05f, 1f) },
                modifier = Modifier
                    .width(180.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = ZehnColors.Cyan,
                trackColor = ZehnColors.SurfaceElevated
            )
            Spacer(Modifier.height(8.dp))
            Text(voiceState.statusBanner, style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
        }

        ClayGlassCard(modifier = Modifier.fillMaxWidth()) {
            if (voiceState.liveTranscript.isNotBlank()) {
                Text("You: ${voiceState.liveTranscript}", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
                Spacer(Modifier.height(6.dp))
            }
            Text(
                text = voiceState.lastReply.ifBlank {
                    if (planState.goal.isNotBlank()) "Plan: ${planState.goal}\n${planState.summary}"
                    else "Say \"Hey Zehn\", \"Open Spotify\", \"Battery status\", or speak Urdu / Hindi / Hinglish."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = ZehnColors.FrostWhite,
                maxLines = 5
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ClayActionButton(
                    text = if (voiceState.orbState == OrbState.IDLE) "Listen" else "Barge-In / Stop",
                    icon = if (voiceState.orbState == OrbState.IDLE) Icons.Filled.Mic else Icons.Filled.Stop,
                    isDanger = voiceState.orbState != OrbState.IDLE,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (voiceState.orbState == OrbState.IDLE) startMicWithPermission()
                        else vm.voiceCoordinator.bargeIn()
                    }
                )
                ClayActionButton(
                    text = "Back",
                    icon = Icons.Filled.GraphicEq,
                    modifier = Modifier.weight(0.7f),
                    onClick = onBack
                )
            }
        }
    }
}
