package com.zehn.assistant.agent

import com.zehn.assistant.data.db.ActionLogDao
import com.zehn.assistant.data.db.ActionLogEntity
import com.zehn.assistant.data.db.WorkflowEntity
import com.zehn.assistant.data.model.ExecutionPath
import com.zehn.assistant.security.SafetyPolicyManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class PlanStep(
    val index: Int,
    val label: String,
    val status: String // PENDING, RUNNING, DONE, BLOCKED, FAILED
)

data class AgentPlanState(
    val goal: String = "",
    val path: ExecutionPath = ExecutionPath.FAST_PATH,
    val confidence: Float = 0f,
    val steps: List<PlanStep> = emptyList(),
    val running: Boolean = false,
    val summary: String = ""
)

data class ClassifiedIntent(
    val action: String,
    val param1: String = "",
    val param2: String = "",
    val confidence: Float,
    val path: ExecutionPath
)

data class CameraLaunchRequest(
    val useFrontCamera: Boolean,
    val isVideoMode: Boolean,
    val sourceCommand: String
)

@Singleton
class IntentClassifier @Inject constructor() {
    fun classify(input: String): ClassifiedIntent {
        val lower = input.lowercase().trim()
        return when {
            lower == "stop" || lower == "stop all" || lower == "band karo" || lower == "ruk jao" || lower == "bas karo" -> {
                ClassifiedIntent("STOP_ALL", "", "", 0.99f, ExecutionPath.FAST_PATH)
            }
            lower.contains("front camera") || lower.contains("selfie camera") -> {
                val isVideo = lower.contains("video") || lower.contains("record")
                ClassifiedIntent("OPEN_CAMERA_UI", "front", isVideo.toString(), 0.95f, ExecutionPath.FAST_PATH)
            }
            lower.contains("back camera") ||
                ((lower.contains("camera") || lower.contains("photo lo") || lower.contains("take photo") || lower.contains("video record")) &&
                    (lower.contains("open") || lower.contains("kholo") || lower.contains("lo") || lower.contains("record") || lower.contains("karo"))) -> {
                val isFront = lower.contains("front") || lower.contains("selfie")
                val isVideo = lower.contains("video") || lower.contains("record")
                ClassifiedIntent("OPEN_CAMERA_UI", if (isFront) "front" else "back", isVideo.toString(), 0.94f, ExecutionPath.FAST_PATH)
            }
            lower.contains("screenshot") -> {
                ClassifiedIntent("SCREENSHOT", "", "", 0.94f, ExecutionPath.FAST_PATH)
            }
            lower.contains("close app") || lower.contains("app band") || lower.contains("go home") || lower == "home" -> {
                ClassifiedIntent("CLOSE_OR_HOME", "", "", 0.92f, ExecutionPath.FAST_PATH)
            }
            lower == "back" || lower == "go back" || lower == "wapis jao" || lower == "peeche jao" -> {
                ClassifiedIntent("NAV_BACK", "", "", 0.94f, ExecutionPath.FAST_PATH)
            }
            lower.contains("tiktok") && (lower.contains("scroll") || lower.contains("swipe") || lower.contains("next")) -> {
                ClassifiedIntent("TIKTOK_SCROLL", "", "", 0.91f, ExecutionPath.STANDARD_PATH)
            }
            lower.startsWith("open ") || lower.endsWith(" kholo") || lower.endsWith(" open karo") -> {
                val app = lower
                    .removePrefix("open ")
                    .removeSuffix(" open karo")
                    .removeSuffix(" kholo")
                    .trim()
                ClassifiedIntent("OPEN_APP", app, "", 0.94f, ExecutionPath.FAST_PATH)
            }
            lower.contains("youtube") && (lower.contains("play") || lower.contains("search") || lower.contains("par") || lower.contains("chalao")) -> {
                val q = lower
                    .replace("youtube par", "")
                    .replace("on youtube", "")
                    .replace("search karo", "")
                    .replace("search", "")
                    .replace("play", "")
                    .replace("chalao", "")
                    .replace("youtube", "")
                    .trim()
                ClassifiedIntent("YOUTUBE", q.ifBlank { "lofi beats" }, "", 0.91f, ExecutionPath.FAST_PATH)
            }
            lower.contains("google") && (lower.contains("search") || lower.contains("par") || lower.contains("dhundo")) -> {
                val q = lower
                    .replace("google par", "")
                    .replace("on google", "")
                    .replace("search karo", "")
                    .replace("search", "")
                    .replace("dhundo", "")
                    .replace("google", "")
                    .trim()
                ClassifiedIntent("GOOGLE_SEARCH", q.ifBlank { "weather today" }, "", 0.91f, ExecutionPath.FAST_PATH)
            }
            lower.contains("maps") && (lower.contains("search") || lower.contains("par") || lower.contains("directions") || lower.contains("open")) -> {
                val q = lower
                    .replace("google maps", "")
                    .replace("maps par", "")
                    .replace("on maps", "")
                    .replace("maps", "")
                    .replace("open", "")
                    .replace("karo", "")
                    .trim()
                ClassifiedIntent("OPEN_MAPS", q, "", 0.90f, ExecutionPath.FAST_PATH)
            }
            lower.contains("flashlight") || lower.contains("torch") || lower.contains("light jalao") || lower.contains("light band") -> {
                val state = when {
                    lower.contains("on") || lower.contains("jalao") -> "on"
                    lower.contains("off") || lower.contains("band") -> "off"
                    else -> "toggle"
                }
                ClassifiedIntent("FLASHLIGHT", state, "", 0.96f, ExecutionPath.FAST_PATH)
            }
            lower.contains("battery") || lower.contains("system status") || lower.contains("ram") || lower.contains("cpu") -> {
                ClassifiedIntent("SYSTEM_STATUS", "", "", 0.95f, ExecutionPath.FAST_PATH)
            }
            lower.contains("weather") || lower.contains("mausam") -> {
                val city = lower.substringAfter("weather in ", "").ifBlank {
                    lower.substringAfter("weather ", "Karachi")
                }.trim()
                ClassifiedIntent("WEATHER", city, "", 0.90f, ExecutionPath.FAST_PATH)
            }
            lower.startsWith("call ") || lower.endsWith(" ko call karo") -> {
                val num = lower.removePrefix("call ").removeSuffix(" ko call karo").trim()
                ClassifiedIntent("CALL", num, "", 0.92f, ExecutionPath.FAST_PATH)
            }
            lower.startsWith("whatsapp ") -> {
                ClassifiedIntent("WHATSAPP", "", input, 0.88f, ExecutionPath.FAST_PATH)
            }
            lower.contains("spotify") -> {
                val q = lower.replace("play", "").replace("on spotify", "").replace("spotify", "").trim()
                ClassifiedIntent("SPOTIFY", q.ifBlank { "chill mix" }, "", 0.90f, ExecutionPath.FAST_PATH)
            }
            lower.contains("pause") || lower.contains("resume") || lower.contains("next song") -> {
                val act = if (lower.contains("next")) "next" else "toggle"
                ClassifiedIntent("MEDIA", act, "", 0.91f, ExecutionPath.FAST_PATH)
            }
            lower.contains("wifi") || lower.contains("wi-fi") || lower.contains("bluetooth") -> {
                val target = if (lower.contains("bluetooth")) "bluetooth" else "wifi"
                ClassifiedIntent("CONNECTIVITY", target, "", 0.91f, ExecutionPath.FAST_PATH)
            }
            lower.contains("speaker on") || lower.contains("speakerphone") -> {
                ClassifiedIntent("SPEAKERPHONE", (!lower.contains("off")).toString(), "", 0.92f, ExecutionPath.FAST_PATH)
            }
            lower.contains("read notifications") || lower.contains("notifications") -> {
                ClassifiedIntent("READ_NOTIFICATIONS", "", "", 0.89f, ExecutionPath.FAST_PATH)
            }
            lower.contains("clipboard") -> {
                ClassifiedIntent("CLIPBOARD", "", "", 0.90f, ExecutionPath.FAST_PATH)
            }
            lower.contains("tap ") || lower.contains("scroll") || lower.contains("swipe") || lower.contains("type ") || lower.contains("screen") -> {
                ClassifiedIntent("GUI_AUTOMATION", input, "", 0.78f, ExecutionPath.STANDARD_PATH)
            }
            else -> ClassifiedIntent("LLM_CHAT", input, "", 0.60f, ExecutionPath.FAST_PATH)
        }
    }
}

@Singleton
class PlanManager @Inject constructor() {
    private val _planState = MutableStateFlow(AgentPlanState())
    val planState: StateFlow<AgentPlanState> = _planState.asStateFlow()

    fun startPlan(goal: String, path: ExecutionPath, confidence: Float, stepLabels: List<String>) {
        _planState.value = AgentPlanState(
            goal = goal,
            path = path,
            confidence = confidence,
            steps = stepLabels.mapIndexed { idx, label ->
                PlanStep(idx + 1, label, if (idx == 0) "RUNNING" else "PENDING")
            },
            running = true,
            summary = ""
        )
    }

    fun updateStep(index: Int, status: String) {
        _planState.update { state ->
            val updated = state.steps.map {
                if (it.index == index) it.copy(status = status) else it
            }
            state.copy(steps = updated)
        }
    }

    fun finishPlan(summary: String) {
        _planState.update { it.copy(running = false, summary = summary) }
    }
}

@Singleton
class VisionEngine @Inject constructor(
    private val safetyPolicy: SafetyPolicyManager
) {
    fun inspectScreenSummary(): String {
        val svc = ZehnAccessibilityService.instance.value
            ?: return "Accessibility Service is not enabled."
        val pkg = ZehnAccessibilityService.foregroundPackage.value
        if (!safetyPolicy.canReadCurrentScreen(pkg)) {
            return "Screen reading blocked by Safety/Gaming Guard for ${pkg ?: "current app"}."
        }
        val nodes = svc.readScreenSnapshots()
        if (nodes.isEmpty()) return "Screen appears empty or protected."
        return nodes.take(25).joinToString(" | ") {
            it.text.ifBlank { it.contentDescription }.ifBlank { it.viewId }
        }
    }
}

@Singleton
class AgentLoop @Inject constructor(
    private val intentClassifier: IntentClassifier,
    private val planManager: PlanManager,
    private val visionEngine: VisionEngine,
    private val phoneControl: PhoneControlExecutor,
    private val safetyPolicy: SafetyPolicyManager,
    private val actionLogDao: ActionLogDao
) {
    private val _recordingActive = MutableStateFlow(false)
    val recordingActive: StateFlow<Boolean> = _recordingActive.asStateFlow()

    private val _cameraLaunchRequests = MutableSharedFlow<CameraLaunchRequest>(extraBufferCapacity = 4)
    val cameraLaunchRequests: SharedFlow<CameraLaunchRequest> = _cameraLaunchRequests.asSharedFlow()

    private val recordedSteps = mutableListOf<String>()

    fun startRecordingTask() {
        recordedSteps.clear()
        _recordingActive.value = true
    }

    fun stopRecordingAsYaml(name: String): String {
        _recordingActive.value = false
        val body = if (recordedSteps.isEmpty()) {
            "  - action: get_system_status"
        } else {
            recordedSteps.joinToString("\n") { "  - command: \"$it\"" }
        }
        return "name: $name\nmode: instant_replay\nsteps:\n$body"
    }

    suspend fun tryExecuteCommand(input: String): String? {
        val intent = intentClassifier.classify(input)
        if (intent.action == "STOP_ALL") {
            safetyPolicy.triggerEmergencyStop()
            return "STOP activated — voice, agent tasks, and tools halted immediately."
        }
        if (safetyPolicy.policy.value.emergencyStopped) {
            return "Emergency STOP is active. Clear STOP in Security or Home before running actions."
        }
        if (_recordingActive.value) {
            recordedSteps.add(input)
        }
        if (intent.action == "LLM_CHAT") return null

        if (intent.action == "TIKTOK_SCROLL") {
            phoneControl.openApp("tiktok")
            delay(500)
            return runGuiLoop("swipe up")
        }

        if (intent.path == ExecutionPath.FAST_PATH && intent.confidence >= 0.85f) {
            planManager.startPlan(
                goal = input,
                path = ExecutionPath.FAST_PATH,
                confidence = intent.confidence,
                stepLabels = listOf("Classify Intent (${intent.action})", "Execute Direct Android Fast-Path")
            )
            planManager.updateStep(1, "DONE")
            val result = executeFastPath(intent, input)
            val status = when {
                result.startsWith("Blocked", true) -> "BLOCKED"
                result.contains("failed", true) || result.contains("not installed", true) || result.contains("Unable", true) -> "FAILED"
                else -> "DONE"
            }
            planManager.updateStep(2, status)
            planManager.finishPlan(result)
            actionLogDao.insert(ActionLogEntity("AGENT_FAST", intent.action, result, if (status == "DONE") "SUCCESS" else status))
            return result
        }

        return runGuiLoop(input)
    }

    private suspend fun executeFastPath(intent: ClassifiedIntent, rawInput: String): String {
        return when (intent.action) {
            "OPEN_APP" -> phoneControl.openApp(intent.param1)
            "OPEN_CAMERA_UI" -> {
                val front = intent.param1 == "front"
                val video = intent.param2.toBoolean()
                _cameraLaunchRequests.tryEmit(CameraLaunchRequest(front, video, rawInput))
                "Opened visible Camera preview (${if (front) "front" else "back"} lens${if (video) ", video ready" else ""}). Tap capture to save."
            }
            "SCREENSHOT" -> {
                val svc = ZehnAccessibilityService.instance.value
                    ?: return "Enable Accessibility Service in Permissions to take screenshots."
                svc.saveScreenshotToGallery().getOrElse { it.message ?: "Screenshot failed" }
            }
            "CLOSE_OR_HOME" -> phoneControl.closeCurrentApp()
            "NAV_BACK" -> {
                val svc = ZehnAccessibilityService.instance.value
                    ?: return "Enable Accessibility Service to press Back."
                if (svc.performBack()) "Navigated Back." else "Back action failed."
            }
            "FLASHLIGHT" -> when (intent.param1) {
                "on" -> phoneControl.toggleFlashlight(true)
                "off" -> phoneControl.toggleFlashlight(false)
                else -> phoneControl.toggleFlashlight(null)
            }
            "SYSTEM_STATUS" -> phoneControl.getSystemStatusSummary()
            "WEATHER" -> phoneControl.fetchWeather(intent.param1)
            "CALL" -> phoneControl.initiateCall(intent.param1)
            "WHATSAPP" -> phoneControl.sendWhatsApp("", intent.param2.removePrefix("whatsapp").trim())
            "YOUTUBE" -> phoneControl.playYouTube(intent.param1)
            "GOOGLE_SEARCH" -> phoneControl.searchGoogle(intent.param1)
            "OPEN_MAPS" -> phoneControl.openMaps(intent.param1)
            "SPOTIFY" -> phoneControl.playSpotify(intent.param1)
            "MEDIA" -> phoneControl.controlMedia(intent.param1)
            "CONNECTIVITY" -> phoneControl.openConnectivityPanel(intent.param1)
            "SPEAKERPHONE" -> phoneControl.setSpeakerphone(intent.param1.toBoolean())
            "READ_NOTIFICATIONS" -> {
                val items = ZehnNotificationListenerService.recentNotifications.value.take(5)
                if (items.isEmpty()) "No recent captured notifications."
                else items.joinToString("\n") { "• [${it.title}] ${it.body}" }
            }
            "CLIPBOARD" -> {
                val clip = phoneControl.getClipboardText()
                if (clip.isBlank()) "Clipboard is empty." else "Clipboard: $clip"
            }
            else -> "Executed ${intent.action}."
        }
    }

    /**
     * 6-stage deterministic UI automation pipeline:
     * 1. Observe  2. Understand  3. Plan  4. Execute  5. Verify  6. Recover
     */
    suspend fun runGuiLoop(goal: String): String {
        val svc = ZehnAccessibilityService.instance.value
        if (svc == null) {
            val msg = "Accessibility Service is disabled. Enable Zehn Accessibility Service in Permissions to run UI automation."
            actionLogDao.insert(ActionLogEntity("AGENT_GUI", "GUI Loop Unavailable", msg, "FAILED"))
            return msg
        }

        planManager.startPlan(
            goal = goal,
            path = ExecutionPath.STANDARD_PATH,
            confidence = 0.80f,
            stepLabels = listOf(
                "1. Observe active UI tree",
                "2. Understand safety & target semantics",
                "3. Plan UI action sequence",
                "4. Execute gesture/accessibility action",
                "5. Verify post-action screen state",
                "6. Recover or report truthful outcome"
            )
        )

        if (safetyPolicy.policy.value.emergencyStopped) {
            planManager.finishPlan("Aborted by Emergency STOP")
            return "Mission aborted by Emergency STOP."
        }

        // Stage 1: Observe
        val beforePkg = ZehnAccessibilityService.foregroundPackage.value.orEmpty()
        val beforeSummary = visionEngine.inspectScreenSummary()
        if (beforeSummary.startsWith("Screen reading blocked")) {
            planManager.updateStep(1, "BLOCKED")
            planManager.finishPlan(beforeSummary)
            return beforeSummary
        }
        planManager.updateStep(1, "DONE")

        // Stage 2: Understand
        val lower = goal.lowercase().trim()
        val actionKind = when {
            lower.contains("scroll down") || lower.contains("swipe up") -> "SCROLL_DOWN"
            lower.contains("scroll up") || lower.contains("swipe down") -> "SCROLL_UP"
            lower.contains("swipe left") -> "SWIPE_LEFT"
            lower.contains("swipe right") -> "SWIPE_RIGHT"
            lower.startsWith("tap ") || lower.contains("click ") -> "TAP"
            lower.startsWith("type ") -> "TYPE"
            lower.contains("recents") -> "RECENTS"
            else -> "INSPECT"
        }
        planManager.updateStep(2, "DONE")

        // Stage 3: Plan
        if (safetyPolicy.policy.value.emergencyStopped) {
            planManager.updateStep(3, "BLOCKED")
            planManager.finishPlan("Aborted by Emergency STOP")
            return "Aborted by Emergency STOP."
        }
        planManager.updateStep(3, "DONE")

        // Stage 4: Execute
        var executedOk = false
        var actionDetail = ""
        when (actionKind) {
            "SCROLL_DOWN" -> {
                executedOk = svc.scroll(true)
                actionDetail = if (executedOk) "Scrolled/swiped forward" else "Forward scroll rejected"
            }
            "SCROLL_UP" -> {
                executedOk = svc.scroll(false)
                actionDetail = if (executedOk) "Scrolled/swiped backward" else "Backward scroll rejected"
            }
            "SWIPE_LEFT" -> {
                executedOk = svc.performSwipe("left")
                actionDetail = if (executedOk) "Swiped left" else "Swipe left failed"
            }
            "SWIPE_RIGHT" -> {
                executedOk = svc.performSwipe("right")
                actionDetail = if (executedOk) "Swiped right" else "Swipe right failed"
            }
            "RECENTS" -> {
                executedOk = svc.performRecents()
                actionDetail = if (executedOk) "Opened Recents" else "Recents failed"
            }
            "TAP" -> {
                val target = goal.substringAfter("tap ", goal.substringAfter("click ", "")).trim()
                when (val res = svc.tapNodeByText(target)) {
                    is ZehnAccessibilityService.TapOutcome.Success -> {
                        executedOk = true
                        actionDetail = res.message
                    }
                    is ZehnAccessibilityService.TapOutcome.Blocked -> {
                        planManager.updateStep(4, "BLOCKED")
                        planManager.updateStep(5, "BLOCKED")
                        planManager.updateStep(6, "BLOCKED")
                        planManager.finishPlan(res.reason)
                        actionLogDao.insert(ActionLogEntity("SAFETY", "Tap Blocked", res.reason, "BLOCKED"))
                        return res.reason
                    }
                    is ZehnAccessibilityService.TapOutcome.Failed -> {
                        executedOk = false
                        actionDetail = res.reason
                    }
                }
            }
            "TYPE" -> {
                val txt = goal.substringAfter("type ", "").trim()
                executedOk = svc.typeTextIntoFocused(txt)
                actionDetail = if (executedOk) "Typed text into focused field" else "No non-password editable field focused"
            }
            else -> {
                executedOk = true
                actionDetail = "Observed screen: ${beforeSummary.take(180)}"
            }
        }
        planManager.updateStep(4, if (executedOk) "DONE" else "FAILED")

        // Stage 5: Verify
        delay(220)
        val afterPkg = ZehnAccessibilityService.foregroundPackage.value.orEmpty()
        val afterSummary = visionEngine.inspectScreenSummary()
        val stateChanged = (afterPkg != beforePkg) || (afterSummary != beforeSummary)
        val verified = executedOk && (actionKind == "INSPECT" || stateChanged || actionKind.startsWith("SWIPE") || actionKind.startsWith("SCROLL"))
        planManager.updateStep(5, if (verified) "DONE" else "FAILED")

        // Stage 6: Recover if needed
        var finalSummary = actionDetail
        if (!verified && (actionKind == "SCROLL_DOWN" || actionKind == "SCROLL_UP")) {
            val recoveryOk = svc.performSwipe(if (actionKind == "SCROLL_DOWN") "up" else "down")
            planManager.updateStep(6, if (recoveryOk) "DONE" else "FAILED")
            finalSummary = if (recoveryOk) "$actionDetail (recovered via gesture swipe)" else "Failed: screen did not respond to scroll or swipe."
        } else {
            planManager.updateStep(6, if (verified) "DONE" else "FAILED")
            if (!verified) {
                finalSummary = "Verification failed: $actionDetail (UI state unchanged)"
            }
        }

        val logStatus = if (verified || finalSummary.contains("recovered")) "SUCCESS" else "FAILED"
        actionLogDao.insert(ActionLogEntity("AGENT_GUI", goal.take(48), finalSummary, logStatus))
        planManager.finishPlan(finalSummary)
        return finalSummary
    }

    suspend fun executeWorkflowOrFlow(workflow: WorkflowEntity): String {
        if (safetyPolicy.policy.value.emergencyStopped) return "Blocked: Emergency STOP active."
        val outputs = mutableListOf<String>()
        if (workflow.kind == "WORKFLOW_JSON") {
            runCatching {
                val obj = JSONObject(workflow.definitionText)
                val steps = obj.optJSONArray("steps") ?: return@runCatching
                for (i in 0 until steps.length()) {
                    val s = steps.getJSONObject(i)
                    val tool = s.optString("tool")
                    val args = s.optJSONObject("args")
                    val res = when (tool) {
                        "get_system_status" -> phoneControl.getSystemStatusSummary()
                        "set_volume" -> phoneControl.setVolumePercent(args?.optInt("level", 60) ?: 60)
                        "open_app" -> phoneControl.openApp(args?.optString("app").orEmpty())
                        "toggle_flashlight" -> phoneControl.toggleFlashlight(null)
                        else -> "Step $tool completed"
                    }
                    outputs.add(res)
                }
            }
        } else {
            workflow.definitionText.lines().forEach { line ->
                val trimmed = line.trim()
                when {
                    trimmed.contains("toggle_flashlight") -> outputs.add(phoneControl.toggleFlashlight(null))
                    trimmed.contains("set_volume") -> outputs.add(phoneControl.setVolumePercent(0))
                    trimmed.contains("get_system_status") -> outputs.add(phoneControl.getSystemStatusSummary())
                    trimmed.startsWith("- command:") -> {
                        val cmd = trimmed.removePrefix("- command:").trim().trim('"')
                        outputs.add(tryExecuteCommand(cmd) ?: "Ran: $cmd")
                    }
                }
            }
        }
        val summary = outputs.ifEmpty { listOf("Executed ${workflow.name}") }.joinToString(" -> ")
        actionLogDao.insert(ActionLogEntity("TOOL", "Replay ${workflow.name}", summary, "SUCCESS"))
        return summary
    }
}
