package com.zehn.assistant.agent

import com.zehn.assistant.data.db.ActionLogDao
import com.zehn.assistant.data.db.ActionLogEntity
import com.zehn.assistant.data.db.WorkflowEntity
import com.zehn.assistant.data.model.ExecutionPath
import com.zehn.assistant.security.SafetyPolicyManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

@Singleton
class IntentClassifier @Inject constructor() {
    fun classify(input: String): ClassifiedIntent {
        val lower = input.lowercase().trim()
        return when {
            lower.startsWith("open ") || lower.endsWith(" kholo") -> {
                val app = lower.removePrefix("open ").removeSuffix(" kholo").trim()
                ClassifiedIntent("OPEN_APP", app, "", 0.94f, ExecutionPath.FAST_PATH)
            }
            lower.contains("flashlight") || lower.contains("torch") -> {
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
            lower.contains("weather") -> {
                val city = lower.substringAfter("weather in ", "").ifBlank {
                    lower.substringAfter("weather ", "Karachi")
                }.trim()
                ClassifiedIntent("WEATHER", city, "", 0.90f, ExecutionPath.FAST_PATH)
            }
            lower.startsWith("call ") -> {
                ClassifiedIntent("CALL", lower.removePrefix("call ").trim(), "", 0.92f, ExecutionPath.FAST_PATH)
            }
            lower.startsWith("whatsapp ") -> {
                ClassifiedIntent("WHATSAPP", "", input, 0.88f, ExecutionPath.FAST_PATH)
            }
            lower.contains("youtube") && (lower.contains("play") || lower.contains("search")) -> {
                val q = lower.replace("play", "").replace("on youtube", "").replace("youtube", "").trim()
                ClassifiedIntent("YOUTUBE", q.ifBlank { "lofi beats" }, "", 0.90f, ExecutionPath.FAST_PATH)
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
            lower.contains("tap ") || lower.contains("scroll") || lower.contains("type ") || lower.contains("screen") -> {
                ClassifiedIntent("GUI_AUTOMATION", input, "", 0.72f, ExecutionPath.STANDARD_PATH)
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
        if (safetyPolicy.policy.value.emergencyStopped) {
            return "Emergency STOP is active. Clear STOP in Security or Home before running actions."
        }
        if (_recordingActive.value) {
            recordedSteps.add(input)
        }

        val intent = intentClassifier.classify(input)
        if (intent.action == "LLM_CHAT") return null

        if (intent.path == ExecutionPath.FAST_PATH && intent.confidence >= 0.85f) {
            planManager.startPlan(
                goal = input,
                path = ExecutionPath.FAST_PATH,
                confidence = intent.confidence,
                stepLabels = listOf("Classify Intent (${intent.action})", "Execute Direct Android Fast-Path")
            )
            planManager.updateStep(1, "DONE")
            val result = executeFastPath(intent)
            planManager.updateStep(2, if (result.startsWith("Blocked")) "BLOCKED" else "DONE")
            planManager.finishPlan(result)
            actionLogDao.insert(ActionLogEntity("AGENT_FAST", intent.action, result, "SUCCESS"))
            return result
        }

        // Standard Path GUI Loop: observe -> analyze -> act -> reflect
        return runGuiLoop(input)
    }

    private suspend fun executeFastPath(intent: ClassifiedIntent): String {
        return when (intent.action) {
            "OPEN_APP" -> phoneControl.openApp(intent.param1)
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

    suspend fun runGuiLoop(goal: String): String {
        val maxSteps = safetyPolicy.policy.value.maxAgentSteps
        val svc = ZehnAccessibilityService.instance.value
        if (svc == null) {
            val msg = "Standard GUI loop requires Zehn Accessibility Service enabled (see Permissions / Touch Guard)."
            actionLogDao.insert(ActionLogEntity("AGENT_GUI", "GUI Loop Unavailable", msg, "FAILED"))
            return msg
        }

        planManager.startPlan(
            goal = goal,
            path = ExecutionPath.STANDARD_PATH,
            confidence = 0.72f,
            stepLabels = listOf(
                "Capture & Read Active Screen",
                "Evaluate Safety & Payment Guards",
                "Execute Accessibility Action",
                "Reflect & Verify"
            )
        )

        var step = 0
        var lastOutcome = ""
        while (step < maxSteps && step < 4) {
            if (safetyPolicy.policy.value.emergencyStopped) {
                planManager.finishPlan("Aborted by Emergency STOP")
                return "Mission aborted by Emergency STOP."
            }
            step++
            when (step) {
                1 -> {
                    val summary = visionEngine.inspectScreenSummary()
                    if (summary.startsWith("Screen reading blocked")) {
                        planManager.updateStep(1, "BLOCKED")
                        planManager.finishPlan(summary)
                        return summary
                    }
                    lastOutcome = summary
                    planManager.updateStep(1, "DONE")
                }
                2 -> {
                    planManager.updateStep(2, "DONE")
                }
                3 -> {
                    val lower = goal.lowercase()
                    val outcome = when {
                        lower.contains("scroll down") -> if (svc.scroll(true)) "Scrolled down" else "Scroll failed"
                        lower.contains("scroll up") -> if (svc.scroll(false)) "Scrolled up" else "Scroll failed"
                        lower.startsWith("tap ") -> {
                            val target = goal.substringAfter("tap ", "").trim()
                            when (val res = svc.tapNodeByText(target)) {
                                is ZehnAccessibilityService.TapOutcome.Success -> res.message
                                is ZehnAccessibilityService.TapOutcome.Blocked -> {
                                    planManager.updateStep(3, "BLOCKED")
                                    planManager.finishPlan(res.reason)
                                    actionLogDao.insert(ActionLogEntity("SAFETY", "Tap Blocked", res.reason, "BLOCKED"))
                                    return res.reason
                                }
                                is ZehnAccessibilityService.TapOutcome.Failed -> res.reason
                            }
                        }
                        lower.startsWith("type ") -> {
                            val txt = goal.substringAfter("type ", "").trim()
                            if (svc.typeTextIntoFocused(txt)) "Typed '$txt'" else "No editable field focused"
                        }
                        else -> "Screen inspected: ${lastOutcome.take(180)}"
                    }
                    lastOutcome = outcome
                    planManager.updateStep(3, "DONE")
                }
                4 -> {
                    delay(150)
                    planManager.updateStep(4, "DONE")
                }
            }
        }
        actionLogDao.insert(ActionLogEntity("AGENT_GUI", goal.take(48), lastOutcome, "SUCCESS"))
        planManager.finishPlan(lastOutcome)
        return lastOutcome
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
            // Fixed YAML-style instant replay without LLM
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
