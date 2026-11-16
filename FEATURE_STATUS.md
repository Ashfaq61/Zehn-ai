# MAHI / Zehn AI V4.1 — Complete Feature Audit (`FEATURE_STATUS.md`)

**Package:** `com.zehn.assistant` | **Version:** `4.1.0` (`versionCode = 5`) | **Target SDK:** 35 | **Min SDK:** 24  
**Verified Automated Checks:**
- `compile_applet` (Debug Kotlin + KSP + Hilt + Manifest + Resources): **PASSED**
- `gradle :app:testDebugUnitTest :app:assembleDebug --console=plain`: **35 / 35 tests PASSED** (0 failures, 0 skipped) across `ExampleUnitTest` (1), `SafetyAndIntentTest` (3), `ZehnRobolectricTest` (2), `ZehnCriticalFeaturesTest` (7), `ZehnV33FeaturesTest` (6), `ZehnV40AutonomousUpgradeTest` (4), and `ZehnV41AccessibilityRepairTest` (12, executed on both API 28 and API 35 Robolectric profiles).
- Installable Debug APK assembled & verified (`app-debug.apk`, 85 MB).

> Physical device features (OS-bound Accessibility Service connection, live `dispatchGesture` execution across third-party apps such as WhatsApp, Settings, Browser, and YouTube, live 16kHz/24kHz Gemini Live audio, CameraX preview hardware, MediaProjection consent dialog, and CI GitHub Actions runner execution) cannot run in this headless cloud environment and are honestly classified as `2. Implemented but not tested.`

---

## Classification Key

1. **1. Fully implemented and tested.** — Implemented in production code and verified in Robolectric/JUnit unit tests during this build.
2. **2. Implemented but not tested.** — Compiled cleanly and wired end-to-end in Kotlin/Compose/Manifest, requiring a physical Android device (Android 7–16) or external live credentials/CI runner.
3. **3. Partially implemented.** — Configuration/bridge hooks present; external runtime component intentionally out-of-bundle.
4. **4. UI placeholder only.** — Zero in V4.1.
5. **5. Missing.** — Capability deliberately not bundled.
6. **6. Blocked by Android or device restrictions.** — Restricted by standard non-root Android security boundaries.

---

## Detailed Subsystem Audit

### 1. Screen Vision, Accessibility Automation & Gesture Repair (V4.1)

| Feature | Classification | Notes & Evidence |
|---|---|---|
| Accessibility Manifest Registration & Config (`AndroidManifest.xml`, `accessibility_service_config.xml`) | **1. Fully implemented and tested.** *(manifest, exported="true", permission & XML caps)* / **2. Implemented but not tested.** *(live OS Accessibility Settings toggle)* | Fixed `android:exported="true"` with `BIND_ACCESSIBILITY_SERVICE`, removed deprecated `flagRequestEnhancedWebAccessibility`, enabled `canPerformGestures`, `canRetrieveWindowContent`, `canTakeScreenshot`, and clean `onUnbind`/`onInterrupt`/`refreshServiceConfiguration()`. Verified on API 28 & API 35 in `ZehnV41AccessibilityRepairTest`. |
| Permission vs Connected-State Detection (`ZehnAccessibilityService`) | **1. Fully implemented and tested.** *(Settings.Secure + AccessibilityManager separation)* / **2. Implemented but not tested.** *(live detached-service recovery)* | Accurately distinguishes OS-level enabled setting (`ENABLED_ACCESSIBILITY_SERVICES`) from live bound `isConnected` state across `AccessibilityDebugScreen`, `DiagnosticsScreen`, `TouchGuardScreen`, and `PermissionsScreen`. |
| Structured Screen Reading & Password Masking (`observeScreenStructured`) | **1. Fully implemented and tested.** *(password/PIN/OTP heuristics, disconnected guard)* / **2. Implemented but not tested.** *(live external app node tree)* | Multi-window `windows` fallback when `rootInActiveWindow` is null, child/depth safety limits, bounds/actions/input/scrollable node extraction, and strict masking (`[PROTECTED_PASSWORD]`, `[PROTECTED_FIELD]`). |
| Verified Click, Scroll, Swipe & Long-Press (`ZehnAccessibilityService`) | **1. Fully implemented and tested.** *(disconnected failure + Room logging)* / **2. Implemented but not tested.** *(on-device Accessibility actions & gestures)* | Walks up to 7 parent hops for `isClickable`/`isLongClickable`, calls `node.refresh()`, clamps gestures inside `WindowManager.currentWindowMetrics` screen bounds, awaits `GestureResultCallback` with timeouts via `suspendCancellableCoroutine`, and enforces `SafetyPolicyManager` payment gates. |
| Screenshot Capture & On-Device ML Kit OCR (`ScreenCaptureService`, `ScreenVisionManager`) | **1. Fully implemented and tested.** *(manifest mediaProjection FGS declaration, consent status, unavailable error)* / **2. Implemented but not tested.** *(live screen capture + ML Kit OCR + Vision LLM)* | Explicit Separation: Method A (Accessibility tree) vs Method B (`MediaProjection` Foreground Service with `MediaProjection.Callback.onStop()` cleanup + Android 11+ `AccessibilityService.takeScreenshot` fallback + ML Kit `TextRecognition`). |
| Bilingual Compound Intent & 6-Stage `AgentLoop` (`AgentEngine`, `GeminiLiveAudioClient`) | **1. Fully implemented and tested.** *(compound & Roman Urdu parsing, target extraction, disconnected fail)* / **2. Implemented but not tested.** *(end-to-end live third-party app control)* | Fixed compound routes like `"WhatsApp kholo aur chat list scroll karo"` and `"Screen par Settings button click karo"`, added 7 dedicated Accessibility/OCR tools to Gemini Live Voice, and wired real `OBSERVE -> UNDERSTAND -> PLAN -> EXECUTE -> VERIFY -> RECOVER` on `Dispatchers.Default`. |
| Accessibility Debug Dashboard (`AccessibilityDebugScreen`) | **2. Implemented but not tested.** *(compiled, routed in Hub/Diagnostics/Touch Guard & APK built)* | Displays all 11 live telemetry fields, all 7 real test buttons (`Test Screen Reading`, `Test Click`, `Test Scroll Down`, `Test Scroll Up`, `Test Swipe Left`, `Test Swipe Right`, `Reconnect Service`), `MediaProjection` + OCR controls, and built-in interactive test targets (counter button, 20-item scrollable list, horizontal card strip). |

### 2. Autonomous GitHub Research, Self-Upgrade & APK Build Engine (V4.0 Preserved)

| Feature | Classification | Notes & Evidence |
|---|---|---|
| Safety, License, Security & Duplicate Analyzer (`RepoSafetyAnalyzer`) | **1. Fully implemented and tested.** | Verified in `ZehnV40AutonomousUpgradeTest`. |
| Autonomous Research Agent (`AutonomousResearchAgent`) | **1. Fully implemented and tested.** *(parsing, inspection, Room dedup)* / **2. Implemented but not tested.** *(live internet scan)* | Covers 14 GitHub topics + 6 Hugging Face tracks with Room caching. |
| GitHub Auto-Dev Engine (`GitHubAutoDevEngine`) | **1. Fully implemented and tested.** *(scripted Git Data API & rollback)* / **2. Implemented but not tested.** *(live remote repo)* | Branch-isolated commits (`zehn-auto/...`), PRs, bounded CI log self-repair, approval-gated merge, rollback. |
| Release & Update Notification Manager (`ReleaseUpdateManager`) | **1. Fully implemented and tested.** *(semver, trusted origin)* / **2. Implemented but not tested.** *(live tray notification + download)* | Trusted GitHub URL & `minSdk` checks + standard OS install flow. |
| GitHub Actions CI/CD (`.github/workflows/android-build.yml`) | **2. Implemented but not tested.** *(updated for `4.1.*`; runs on GitHub Actions)* | Runs `gradle` wrapper-independent tests, `lintDebug`, deps audit, `assembleDebug` + `aapt2` check, optional signed `assembleRelease`, artifact uploads. |

### 3. Core AI, Security, Voice, Camera & Call Systems (V1.0–V3.3 Preserved)

| Feature | Classification | Notes & Evidence |
|---|---|---|
| Global Crash Logger & Recovery (`ZehnApplication`, `CrashRecoveryManager`) | **1. Fully implemented and tested.** | Verified in `ZehnCriticalFeaturesTest`. |
| Keystore AES-256-GCM Secret Vault (`KeystoreCryptoManager`) | **1. Fully implemented and tested.** | Encrypted storage & Gemini multi-key rotation verified. |
| Multi-Provider AI Brain & Room RAG (`CloudLlmClient`, `AiEngineRouter`, `RagEngine`) | **1. Fully implemented and tested.** *(Room RAG, offline guard)* / **2. Implemented but not tested.** *(live external LLM HTTP & native LiteRT-LM)* | Supports Gemini, OpenAI, Groq, OpenRouter, Hugging Face, Anthropic, Together, Cohere, Ollama, and LiteRT-LM. |
| Bilingual Voice Control & Gemini Live Audio | **1. Fully implemented and tested.** *(LanguageDetector, STOP, barge-in, model list)* / **2. Implemented but not tested.** *(live mic PCM hardware)* | WebSocket Live audio with automatic Classic STT/LLM/TTS fallback. |
| CameraX Vision & Call Screening (`ZehnCallScreeningService`, `CallAssistantManager`) | **1. Fully implemented and tested.** *(VIP/permission/uplink audit)* / **2. Implemented but not tested.** *(live calls/camera hardware)* | CameraX + ML Kit OCR/QR + Telecom screening. |

### 4. Blocked by Android OS or Device Restrictions

| Capability | Classification | Technical Reason & Implemented Alternative |
|---|---|---|
| Self-Rewriting Installed APK Binary / Silent APK Install | **6. Blocked by Android or device restrictions.** | Handled exclusively via GitHub Auto-Dev PRs, GitHub Actions CI builds, and standard Android install confirmation. |
| Injecting TTS Audio Directly into Cellular Call Uplink Microphone | **6. Blocked by Android or device restrictions.** | Blocked by non-root Android Telecom APIs (`canInjectAudioIntoCellularUplink = false`). |
| Force-Stopping Arbitrary Third-Party Apps / Silent Background Mic or Camera | **6. Blocked by Android or device restrictions.** | Restricted to system/signature privileges; Zehn uses Home/Back global actions and visible foreground notifications. |
