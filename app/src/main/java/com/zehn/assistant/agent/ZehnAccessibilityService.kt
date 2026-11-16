package com.zehn.assistant.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.zehn.assistant.security.SafetyPolicyManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

data class ScreenNodeSnapshot(
    val text: String,
    val contentDescription: String,
    val viewId: String,
    val className: String,
    val clickable: Boolean,
    val editable: Boolean,
    val isPassword: Boolean,
    val bounds: Rect
)

class ZehnAccessibilityService : AccessibilityService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ServiceDeps {
        fun safetyPolicy(): SafetyPolicyManager
    }

    private val deps by lazy {
        EntryPointAccessors.fromApplication(applicationContext, ServiceDeps::class.java)
    }

    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance.value = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        _foregroundPackage.value = pkg
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            onAppOpenedCallback?.invoke(pkg)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (_instance.value === this) {
            _instance.value = null
        }
        screenshotExecutor.shutdown()
        super.onDestroy()
    }

    fun readScreenSnapshots(): List<ScreenNodeSnapshot> {
        val currentPkg = _foregroundPackage.value
        if (!deps.safetyPolicy().canReadCurrentScreen(currentPkg)) {
            return emptyList()
        }
        val root = rootInActiveWindow ?: return emptyList()
        val list = mutableListOf<ScreenNodeSnapshot>()
        traverse(root, list, depth = 0)
        return list
    }

    private fun traverse(node: AccessibilityNodeInfo?, out: MutableList<ScreenNodeSnapshot>, depth: Int) {
        if (node == null || depth > 25 || out.size > 120) return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val isPwd = node.isPassword || isPasswordViewId(node.viewIdResourceName)
        val rawText = node.text?.toString().orEmpty()
        val text = if (isPwd && rawText.isNotEmpty()) "[PROTECTED_PASSWORD]" else rawText
        val desc = if (isPwd) "[PROTECTED_FIELD]" else node.contentDescription?.toString().orEmpty()
        val id = node.viewIdResourceName.orEmpty()
        if (text.isNotBlank() || desc.isNotBlank() || node.isClickable || node.isEditable) {
            out.add(
                ScreenNodeSnapshot(
                    text = text,
                    contentDescription = desc,
                    viewId = id,
                    className = node.className?.toString().orEmpty(),
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    isPassword = isPwd,
                    bounds = rect
                )
            )
        }
        for (i in 0 until node.childCount) {
            traverse(node.getChild(i), out, depth + 1)
        }
    }

    fun tapNodeByText(query: String): TapOutcome {
        val policy = deps.safetyPolicy()
        val currentPkg = _foregroundPackage.value
        if (!policy.canReadCurrentScreen(currentPkg)) {
            return TapOutcome.Blocked("Screen reading blocked by Safety/Gaming Guard for $currentPkg")
        }
        val root = rootInActiveWindow ?: return TapOutcome.Failed("No active window")
        val target = findNodeMatching(root, query) ?: return TapOutcome.Failed("Element '$query' not found")

        val safeLabel = if (target.isPassword) "[PROTECTED_FIELD]" else (target.text?.toString() ?: target.contentDescription?.toString() ?: query)
        val nodeId = target.viewIdResourceName
        if (!policy.policy.value.automatedPaymentConfirmEnabled &&
            policy.isSensitivePaymentNode(safeLabel, target.contentDescription?.toString(), nodeId)
        ) {
            return TapOutcome.Blocked("Automated Payment Confirmation is OFF — blocked tapping '$safeLabel'")
        }

        val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) return TapOutcome.Success("Tapped '$safeLabel'")

        val rect = Rect()
        target.getBoundsInScreen(rect)
        return if (tapCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())) {
            TapOutcome.Success("Gesture-tapped '$safeLabel'")
        } else {
            TapOutcome.Failed("Click failed on '$query'")
        }
    }

    private fun findNodeMatching(node: AccessibilityNodeInfo?, query: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (!node.isPassword) {
            val t = node.text?.toString().orEmpty()
            val d = node.contentDescription?.toString().orEmpty()
            if (t.contains(query, ignoreCase = true) || d.contains(query, ignoreCase = true)) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            findNodeMatching(node.getChild(i), query)?.let { return it }
        }
        return null
    }

    fun tapCoordinates(x: Float, y: Float): Boolean {
        if (deps.safetyPolicy().policy.value.emergencyStopped) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun performSwipe(direction: String): Boolean {
        if (deps.safetyPolicy().policy.value.emergencyStopped) return false
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat().coerceAtLeast(720f)
        val h = dm.heightPixels.toFloat().coerceAtLeast(1280f)
        val path = Path()
        when (direction.lowercase()) {
            "up", "down_feed", "next" -> {
                path.moveTo(w * 0.5f, h * 0.72f)
                path.lineTo(w * 0.5f, h * 0.25f)
            }
            "down", "prev" -> {
                path.moveTo(w * 0.5f, h * 0.28f)
                path.lineTo(w * 0.5f, h * 0.75f)
            }
            "left" -> {
                path.moveTo(w * 0.82f, h * 0.5f)
                path.lineTo(w * 0.18f, h * 0.5f)
            }
            "right" -> {
                path.moveTo(w * 0.18f, h * 0.5f)
                path.lineTo(w * 0.82f, h * 0.5f)
            }
            else -> {
                path.moveTo(w * 0.5f, h * 0.72f)
                path.lineTo(w * 0.5f, h * 0.25f)
            }
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 260L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return scrollRecursive(root, action) || performSwipe(if (forward) "up" else "down")
    }

    private fun scrollRecursive(node: AccessibilityNodeInfo?, action: Int): Boolean {
        if (node == null) return false
        if (node.isScrollable && node.performAction(action)) return true
        for (i in 0 until node.childCount) {
            if (scrollRecursive(node.getChild(i), action)) return true
        }
        return false
    }

    fun typeTextIntoFocused(text: String): Boolean {
        if (deps.safetyPolicy().policy.value.emergencyStopped) return false
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFirstEditable(root) ?: return false
        if (focused.isPassword || isPasswordViewId(focused.viewIdResourceName)) {
            return false // Never inject text blindly into password fields
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && !node.isPassword) return node
        for (i in 0 until node.childCount) {
            findFirstEditable(node.getChild(i))?.let { return it }
        }
        return null
    }

    fun performBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun performHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun performRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    suspend fun captureScreenshotBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            return null
        }
        if (!deps.safetyPolicy().canReadCurrentScreen(_foregroundPackage.value)) return null
        return suspendCancellableCoroutine { cont ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hwBmp = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        screenshot.hardwareBuffer.close()
                        val softCopy = hwBmp?.copy(Bitmap.Config.ARGB_8888, false)
                        hwBmp?.recycle()
                        if (cont.isActive) cont.resume(softCopy)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            )
        }
    }

    suspend fun saveScreenshotToGallery(): Result<String> {
        if (!deps.safetyPolicy().canReadCurrentScreen(_foregroundPackage.value)) {
            return Result.failure(IllegalStateException("Screenshot blocked on protected/banking screen."))
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val ok = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            return if (ok) Result.success("Triggered system screenshot.")
            else Result.failure(IllegalStateException("System screenshot action unavailable."))
        }
        val bmp = captureScreenshotBitmap()
            ?: return Result.failure(IllegalStateException("Failed to capture display frame."))
        return runCatching {
            val fileName = "Zehn_Screen_${System.currentTimeMillis()}.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MahiZehnAI")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert returned null")
            contentResolver.openOutputStream(uri)?.use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 95, out)
            } ?: error("Unable to open screenshot output stream")
            bmp.recycle()
            "Saved screenshot: Pictures/MahiZehnAI/$fileName"
        }
    }

    sealed class TapOutcome {
        data class Success(val message: String) : TapOutcome()
        data class Blocked(val reason: String) : TapOutcome()
        data class Failed(val reason: String) : TapOutcome()
    }

    companion object {
        private val _instance = MutableStateFlow<ZehnAccessibilityService?>(null)
        val instance: StateFlow<ZehnAccessibilityService?> = _instance.asStateFlow()

        private val _foregroundPackage = MutableStateFlow<String?>(null)
        val foregroundPackage: StateFlow<String?> = _foregroundPackage.asStateFlow()

        var onAppOpenedCallback: ((String) -> Unit)? = null

        internal fun isPasswordViewId(viewId: String?): Boolean {
            val v = viewId?.lowercase() ?: return false
            return v.contains("password") ||
                v.contains("passcode") ||
                v.contains("pin_input") ||
                v.contains("pin_entry") ||
                v.contains("otp")
        }
    }
}
