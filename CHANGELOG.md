# CHANGELOG — Zehn AI (`com.zehn.assistant`)

## V4.1 — Screen Vision & Android Accessibility Automation Repair (`versionCode = 5`)

### Fixed
- **Accessibility Service Binding (`AndroidManifest.xml`, `accessibility_service_config.xml`, `ZehnAccessibilityService`)**:
  - Changed `ZehnAccessibilityService` from `android:exported="false"` to `android:exported="true"` with `BIND_ACCESSIBILITY_SERVICE` permission so Android 7–16 binds the service reliably.
  - Removed deprecated `flagRequestEnhancedWebAccessibility`, configured `flagReportViewIds|flagRetrieveInteractiveWindows|flagIncludeNotImportantViews`, `canPerformGestures="true"`, `canRetrieveWindowContent="true"`, `canTakeScreenshot="true"`, and clean `onUnbind`/`onInterrupt`/`onDestroy`/`refreshServiceConfiguration()` lifecycle tracking.
- **Silent False-Positive Clicks, Scrolls & Swipes**:
  - Replaced direct leaf-node clicking and unverified fire-and-forget `dispatchGesture(..., null, null)` calls with clickable/long-clickable ancestor discovery (up to 7 parent hops), `AccessibilityNodeInfo.refresh()`, verified `GestureResultCallback` suspension (`suspendCancellableCoroutine` + timeouts), largest visible scrollable container prioritization, and `WindowManager.currentWindowMetrics` visible-bounds coordinate clamping.
  - Added multi-window `windows` fallback when `rootInActiveWindow` returns null, plus strict password/PIN/OTP masking (`isPassword`, `isPasswordViewId`, `isPasswordInputType`).
- **Compound & Bilingual GUI Voice Commands (`AgentEngine`)**:
  - Fixed `IntentClassifier.classify()` so compound commands such as `"WhatsApp kholo aur chat list scroll karo"` and `"Open Settings and tap Wi-Fi"` route to `COMPOUND_GUI` (`STANDARD_PATH`) instead of misrouting to SMS/WhatsApp message sending.
  - Fixed `extractClickTarget()` for English/Roman-Urdu targets (e.g., `"Screen par Settings button click karo"` -> `"Settings"`).
  - Upgraded `AgentLoop.runGuiLoop()` to a non-blocking `Dispatchers.Default` 6-stage `OBSERVE -> UNDERSTAND -> PLAN -> EXECUTE -> VERIFY -> RECOVER` pipeline with genuine post-action screen state checks and OCR recovery fallback.

### Added
- **Two-Method Screen Vision & OCR Engine (`ScreenCaptureService`, `ScreenVisionManager`)**:
  - Explicitly separated **Method A** (Accessibility tree observation) from **Method B** (`MediaProjection` Foreground Service with `foregroundServiceType="mediaProjection"` & `MediaProjection.Callback.onStop()` cleanup, plus Android 11+ `AccessibilityService.takeScreenshot` fallback) paired with on-device ML Kit `TextRecognition` OCR and optional Vision LLM analysis.
- **Accessibility Debug Dashboard (`AccessibilityDebugScreen`)**:
  - Routed in `MainActivity` (`accessibility_debug`), Command Hub, `DiagnosticsScreen`, and `TouchGuardScreen`.
  - Shows all 11 live telemetry fields (permission enabled/disabled, service connected/disconnected, foreground app, node counts, last action & result, gesture capability, screenshot permission status, last error), all 7 real test actions (`Test Screen Reading`, `Test Click`, `Test Scroll Down`, `Test Scroll Up`, `Test Swipe Left`, `Test Swipe Right`, `Reconnect Service`), `MediaProjection` + OCR buttons, and built-in interactive test targets (live counter button, 20-item scrollable list, horizontal card strip).
- **Gemini Live Screen Vision Tools (`GeminiLiveAudioClient`)**:
  - Added `observe_screen`, `click_screen_element`, `scroll_screen`, `swipe_screen`, `long_press_screen`, `navigate_back`, and `capture_screen_ocr` tool declarations and handlers.
- **V4.1 Automated Test Suite (`ZehnV41AccessibilityRepairTest`)**:
  - Added 12 Robolectric multi-SDK tests (`@Config(sdk = [28, 35])`); total verified test suite expanded to **35 / 35 passing** alongside verified `app-debug.apk`.

---

## V4.0 — Autonomous GitHub Research, Self-Upgrade & APK Build Engine
*(Preserved in full: `AutonomousResearchAgent`, `RepoSafetyAnalyzer`, `GitHubAutoDevEngine`, `ReleaseUpdateManager`, `UpgradeScheduler`, `AutonomousUpgradeCenterScreen`, Room v2 migration, and CI/CD workflow.)*

---

## V3.3 — Autonomous Android Assistant Upgrade
*(Preserved in full: HTTPS network security config, Gemini `x-goog-api-key` headers, Hugging Face Inference Router, key-slot CRUD + latency/usage metrics, bilingual voice commands, Continuous Conversation mode, CameraX photo/video/flash/lens flip, Call Screening + VIP filter + Auto-Answer, Open-Source Discovery UI, and Honest Diagnostics.)*
