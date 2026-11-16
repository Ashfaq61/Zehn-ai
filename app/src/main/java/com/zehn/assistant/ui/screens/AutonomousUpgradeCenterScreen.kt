package com.zehn.assistant.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zehn.assistant.BuildConfig
import com.zehn.assistant.security.KeystoreCryptoManager
import com.zehn.assistant.ui.ZehnViewModel
import com.zehn.assistant.ui.components.ClayActionButton
import com.zehn.assistant.ui.components.ClayGlassCard
import com.zehn.assistant.ui.components.ClayToggleSwitch
import com.zehn.assistant.ui.theme.ZehnColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AutonomousUpgradeCenterScreen(vm: ZehnViewModel) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val keysVer by vm.keysVersion.collectAsStateWithLifecycle()
    val tools by vm.discoveredTools.collectAsStateWithLifecycle()
    val proposals by vm.upgradeProposals.collectAsStateWithLifecycle()
    val devRuns by vm.devRunHistory.collectAsStateWithLifecycle()
    val releases by vm.releaseHistory.collectAsStateWithLifecycle()
    val research by vm.researchState.collectAsStateWithLifecycle()
    val ghConn by vm.githubConnection.collectAsStateWithLifecycle()
    val devState by vm.autoDevState.collectAsStateWithLifecycle()

    val hasToken = remember(keysVer) { vm.crypto.hasSecret(KeystoreCryptoManager.KEY_GITHUB_TOKEN) }
    var repoDraft by remember(settings.githubRepoSlug) { mutableStateOf(settings.githubRepoSlug) }
    var branchDraft by remember(settings.githubBaseBranch) { mutableStateOf(settings.githubBaseBranch) }
    var tokenDraft by remember { mutableStateOf("") }
    var searchTopicDraft by remember { mutableStateOf("") }
    var activeTab by remember { mutableIntStateOf(0) } // 0=Proposals, 1=Tools, 2=Releases, 3=Logs

    val notifPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val fmt = remember { SimpleDateFormat("MMM dd HH:mm", Locale.getDefault()) }
    fun formatTs(ts: Long): String = if (ts <= 0L) "Not yet" else fmt.format(Date(ts))

    val approvedCount = proposals.count { it.status == "APPROVED" || it.status == "STAGED" || it.status == "MERGED" }
    val integratedCount = proposals.count { it.status == "STAGED" || it.status == "MERGED" }
    val passedBuildRuns = devRuns.count { it.actionType == "BUILD_POLL" && it.success }
    val latestReleaseVer = releases.firstOrNull()?.tagName ?: "v${BuildConfig.VERSION_NAME} (local)"

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 124.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Autonomous Upgrade Center", style = MaterialTheme.typography.headlineMedium, color = ZehnColors.FrostWhite)
            Text(
                "DISCOVER -> ANALYZE -> PLAN -> IMPLEMENT -> TEST -> REVIEW -> BUILD -> NOTIFY",
                style = MaterialTheme.typography.labelSmall,
                color = ZehnColors.Cyan
            )
        }

        // Live Telemetry & Metrics Grid
        item {
            ClayGlassCard(accentGlow = true) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("GitHub: ${if (ghConn.connected) "@${ghConn.username}" else "Not Verified"}", style = MaterialTheme.typography.titleMedium, color = if (ghConn.connected) ZehnColors.Emerald else ZehnColors.Amber)
                        Text("Research: ${research.currentStage}", style = MaterialTheme.typography.bodySmall, color = ZehnColors.SlateMuted)
                        Text("Cycle: ${devState.activeCycleStage} • Latest APK: $latestReleaseVer", style = MaterialTheme.typography.labelSmall, color = ZehnColors.Cyan)
                    }
                    ClayActionButton(
                        text = "STOP",
                        icon = Icons.Filled.Stop,
                        isDanger = true,
                        modifier = Modifier.testTag("upgrade_emergency_stop_btn"),
                        onClick = { vm.stopResearchAndAutoDev() }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Last scan: ${formatTs(settings.lastResearchTimestamp)} • Next: ${if (settings.researchPaused) "PAUSED" else formatTs(settings.nextScheduledResearchTimestamp)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZehnColors.SlateMuted
                )
                Text(
                    "Discovered: ${tools.size} • Approved: $approvedCount • Integrated: $integratedCount • CI Checks Passed: $passedBuildRuns",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZehnColors.FrostWhite
                )
                if (research.running || devState.running) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = ZehnColors.Cyan)
                }
            }
        }

        // Primary 10 Control Actions Grid
        item {
            ClayGlassCard {
                Text("Autonomous Controls", style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ClayActionButton("Start Research", icon = Icons.Filled.PlayArrow, modifier = Modifier.weight(1f).testTag("btn_start_research"), onClick = {
                        vm.startResearch(includeGitHub = true, includeHuggingFace = true, customQuery = searchTopicDraft.takeIf { it.isNotBlank() })
                    })
                    ClayActionButton("Stop Research", icon = Icons.Filled.Stop, isDanger = true, modifier = Modifier.weight(1f).testTag("btn_stop_research"), onClick = {
                        vm.stopResearchAndAutoDev()
                    })
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ClayActionButton("Scan GitHub", icon = Icons.Filled.Search, modifier = Modifier.weight(1f), onClick = {
                        vm.startResearch(includeGitHub = true, includeHuggingFace = false, customQuery = searchTopicDraft.takeIf { it.isNotBlank() })
                    })
                    ClayActionButton("Scan Hugging Face", icon = Icons.Filled.Search, modifier = Modifier.weight(1f), onClick = {
                        vm.startResearch(includeGitHub = false, includeHuggingFace = true, customQuery = searchTopicDraft.takeIf { it.isNotBlank() })
                    })
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ClayActionButton("Review New Features", modifier = Modifier.weight(1f), onClick = { activeTab = 0 })
                    ClayActionButton("Build APK", icon = Icons.Filled.Build, modifier = Modifier.weight(1f), onClick = { vm.triggerBuildApk() })
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ClayActionButton("Check Updates", icon = Icons.Filled.SystemUpdate, modifier = Modifier.weight(1f), onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        vm.checkForApkUpdates()
                    })
                    ClayActionButton("Restore Previous Version", icon = Icons.Filled.Restore, isDanger = true, modifier = Modifier.weight(1f), onClick = {
                        vm.restorePreviousVersion()
                    })
                }
                val topPending = proposals.firstOrNull { it.status == "PENDING" }
                if (topPending != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        ClayActionButton("Approve Integration", icon = Icons.Filled.Check, modifier = Modifier.weight(1f), onClick = {
                            vm.approveProposalAndStage(topPending.id, stageToGitHub = true)
                        })
                        ClayActionButton("Reject Integration", icon = Icons.Filled.Close, isDanger = true, modifier = Modifier.weight(1f), onClick = {
                            vm.rejectProposal(topPending.id)
                        })
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchTopicDraft,
                    onValueChange = { searchTopicDraft = it },
                    label = { Text("Optional focus keyword (blank = auto-scan all 14 GH + 6 HF topics)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // GitHub Auth & Schedule Settings
        item {
            ClayGlassCard {
                Text("GitHub Connection & Safety Policy", style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                Text(
                    if (hasToken) "Encrypted PAT saved in Android Keystore (masked, never logged)." else "No GitHub PAT configured yet.",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasToken) ZehnColors.Emerald else ZehnColors.Amber
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = repoDraft, onValueChange = { repoDraft = it }, label = { Text("Repository (owner/repo)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("gh_repo_input"))
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = branchDraft, onValueChange = { branchDraft = it }, label = { Text("Base Branch (default main)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = tokenDraft,
                    onValueChange = { tokenDraft = it },
                    label = { Text(if (hasToken) "Replace GitHub PAT (•••• saved)" else "GitHub Fine-Grained PAT (Contents/PR/Actions)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("gh_token_input")
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ClayActionButton("Test", icon = Icons.Filled.Refresh, modifier = Modifier.weight(1f), onClick = {
                        vm.testGitHubConnection(repoDraft, branchDraft, tokenDraft)
                        tokenDraft = ""
                    })
                    ClayActionButton("Save", icon = Icons.Filled.Check, modifier = Modifier.weight(1f), onClick = {
                        vm.saveGitHubConfig(repoDraft, branchDraft, tokenDraft)
                        tokenDraft = ""
                    })
                    ClayActionButton("Delete", icon = Icons.Filled.Delete, isDanger = true, modifier = Modifier.weight(1f), onClick = {
                        vm.deleteGitHubToken()
                        tokenDraft = ""
                    })
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Master Auto-Dev (low-risk staging)", color = ZehnColors.FrostWhite, style = MaterialTheme.typography.bodyMedium)
                    ClayToggleSwitch(checked = settings.autoDevEnabled, onCheckedChange = {
                        vm.updateAutoDevSafetyFlags(it, settings.autoMergeOnGreen, settings.allowWorkflowEdits, settings.notifyOnUpdateReady, settings.notifyOnBuildFailure)
                    })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto-merge low-risk PR when green", color = ZehnColors.FrostWhite, style = MaterialTheme.typography.bodyMedium)
                    ClayToggleSwitch(checked = settings.autoMergeOnGreen, onCheckedChange = {
                        vm.updateAutoDevSafetyFlags(settings.autoDevEnabled, it, settings.allowWorkflowEdits, settings.notifyOnUpdateReady, settings.notifyOnBuildFailure)
                    })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Notify on Verified APK Release", color = ZehnColors.FrostWhite, style = MaterialTheme.typography.bodyMedium)
                    ClayToggleSwitch(checked = settings.notifyOnUpdateReady, onCheckedChange = {
                        vm.updateAutoDevSafetyFlags(settings.autoDevEnabled, settings.autoMergeOnGreen, settings.allowWorkflowEdits, it, settings.notifyOnBuildFailure)
                    })
                }
                Spacer(Modifier.height(6.dp))
                Text("Research Schedule & Filter", style = MaterialTheme.typography.labelMedium, color = ZehnColors.Cyan)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("MANUAL", "DAILY", "WEEKLY").forEach { mode ->
                        FilterChip(selected = settings.researchScheduleMode == mode && !settings.researchPaused, onClick = {
                            vm.updateResearchSchedule(mode, settings.researchFocusMode, paused = false)
                        }, label = { Text(mode) })
                    }
                    FilterChip(selected = settings.researchFocusMode == "SECURITY_ONLY", onClick = {
                        val nextFocus = if (settings.researchFocusMode == "SECURITY_ONLY") "ALL_AI_TOOLS" else "SECURITY_ONLY"
                        vm.updateResearchSchedule(settings.researchScheduleMode, nextFocus, settings.researchPaused)
                    }, label = { Text("Security Only") })
                    FilterChip(selected = settings.researchPaused, onClick = {
                        vm.updateResearchSchedule(settings.researchScheduleMode, settings.researchFocusMode, !settings.researchPaused)
                    }, leadingIcon = { androidx.compose.material3.Icon(Icons.Filled.Pause, null) }, label = { Text(if (settings.researchPaused) "Paused" else "Pause") })
                }
            }
        }

        // Sub-view Tabs
        item {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = activeTab == 0, onClick = { activeTab = 0 }, label = { Text("Proposals (${proposals.size})") })
                FilterChip(selected = activeTab == 1, onClick = { activeTab = 1 }, label = { Text("Discovered (${tools.size})") })
                FilterChip(selected = activeTab == 2, onClick = { activeTab = 2 }, label = { Text("Releases (${releases.size})") })
                FilterChip(selected = activeTab == 3, onClick = { activeTab = 3 }, label = { Text("Audit Log (${devRuns.size})") })
            }
        }

        when (activeTab) {
            0 -> {
                if (proposals.isEmpty()) {
                    item {
                        ClayGlassCard {
                            Text("No pending integration proposals yet. Tap 'Start Research' to inspect GitHub & Hugging Face safely.", color = ZehnColors.SlateMuted)
                        }
                    }
                } else {
                    items(proposals, key = { it.id }) { prop ->
                        ClayGlassCard {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(prop.title, style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan, modifier = Modifier.weight(1f))
                                Text("${prop.riskLevel} • ${prop.status}", style = MaterialTheme.typography.labelSmall, color = if (prop.riskLevel == "SENSITIVE") ZehnColors.Amber else ZehnColors.Emerald)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(prop.summary, style = MaterialTheme.typography.bodySmall, color = ZehnColors.FrostWhite)
                            if (prop.permissionsRequested.isNotBlank()) {
                                Text("Permissions: ${prop.permissionsRequested}", style = MaterialTheme.typography.labelSmall, color = ZehnColors.Amber)
                            }
                            if (prop.branchName.isNotBlank()) {
                                Text("Branch: ${prop.branchName} • CI: ${prop.lastBuildConclusion.ifBlank { "unbuilt" }}", style = MaterialTheme.typography.labelSmall, color = ZehnColors.SlateMuted)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ClayActionButton("Approve & Stage", modifier = Modifier.weight(1f), onClick = { vm.approveProposalAndStage(prop.id, true) })
                                ClayActionButton("Reject", isDanger = true, modifier = Modifier.weight(1f), onClick = { vm.rejectProposal(prop.id) })
                            }
                        }
                    }
                }
            }
            1 -> {
                items(tools, key = { it.id }) { t ->
                    ClayGlassCard {
                        Text("${t.source}: ${t.identifier}", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
                        Text(
                            "Score ${t.compatibilityScore}/100 • License: ${t.license} • ${if (t.source == "GITHUB") "★ ${t.starsOrDownloads}" else "${t.starsOrDownloads} dl"} • Updated ${t.lastUpdated}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ZehnColors.SlateMuted
                        )
                        Text(t.description, style = MaterialTheme.typography.bodySmall, color = ZehnColors.FrostWhite)
                        Text(
                            "Deps: ${t.dependenciesSummary} • RAM ~${t.estimatedRamMb}MB • Battery: ${t.batteryImpact} • Security: ${t.securityFlags}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (t.securityFlags.startsWith("BLOCKED")) ZehnColors.RoseStop else ZehnColors.Emerald
                        )
                    }
                }
            }
            2 -> {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        ClayActionButton("Check GitHub Releases", modifier = Modifier.weight(1f), onClick = { vm.checkForApkUpdates() })
                        ClayActionButton("Poll CI Status", modifier = Modifier.weight(1f), onClick = { vm.pollBuildStatus() })
                    }
                }
                items(releases, key = { it.id }) { rel ->
                    ClayGlassCard {
                        Text("${rel.tagName} (${rel.releaseName})", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
                        Text("${rel.compatibilityNote} • ${rel.apkAssetName.ifBlank { "GitHub Release" }}", style = MaterialTheme.typography.labelSmall, color = ZehnColors.Emerald)
                        Text(rel.changelog.take(220), style = MaterialTheme.typography.bodySmall, color = ZehnColors.FrostWhite)
                        Spacer(Modifier.height(6.dp))
                        ClayActionButton("Download & Install Update", icon = Icons.Filled.SystemUpdate, onClick = { vm.openReleaseDownload(rel) })
                    }
                }
            }
            else -> {
                items(devRuns, key = { it.id }) { run ->
                    ClayGlassCard {
                        Text("${run.actionType} (${run.branchOrRef}) • HTTP ${run.httpStatus}", style = MaterialTheme.typography.titleSmall, color = if (run.success) ZehnColors.Emerald else ZehnColors.RoseStop)
                        Text(run.summary, style = MaterialTheme.typography.bodySmall, color = ZehnColors.FrostWhite)
                        if (run.detail.isNotBlank()) {
                            Text(run.detail.take(180), style = MaterialTheme.typography.labelSmall, color = ZehnColors.SlateMuted)
                        }
                    }
                }
            }
        }
    }
}
