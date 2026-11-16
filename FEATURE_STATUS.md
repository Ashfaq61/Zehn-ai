# MAHI / Zehn AI V3.3 — Complete Feature Audit (`FEATURE_STATUS.md`)

**Package:** `com.zehn.assistant` | **Target SDK:** 35 | **Min SDK:** 24  
**Verified Automated Checks:**
- `compile_applet` (Debug Kotlin + KSP + Hilt + Manifest + Resources): **PASSED**
- `gradle :app:testDebugUnitTest`: **19 / 19 tests PASSED** (0 failures, 0 skipped) across `ExampleUnitTest` (1), `SafetyAndIntentTest` (3), `ZehnCriticalFeaturesTest` (7), `ZehnRobolectricTest` (2), and `ZehnV33FeaturesTest` (6).

> Hardware-dependent features (live mic audio streaming, physical CameraX lenses, live network calls to external LLM APIs, and on-device Accessibility cross-app gestures) cannot be executed on a headless container without an emulator or physical phone and are strictly classified as `2. Implemented but not tested.` or `6. Blocked by Android or device restrictions.`

---

## Classification Key

1. **1. Fully implemented and tested.** — Implemented in production code and verified in Robolectric/JUnit unit tests during this build.
2. **2. Implemented but not tested.** — Compiled cleanly and wired end-to-end in Kotlin/Compose/Manifest, but requires physical hardware, OEM system settings, or live external API credentials for runtime verification.
3. **3. Partially implemented.** — Configuration, state, or partial flow implemented; full external backend/protocol stack intentionally scoped or externalized.
4. **4. UI placeholder only.** — Interactive UI with no backing logic (kept at zero in V3.3).
5. **5. Missing.** — Capability not bundled in this APK.
6. **6. Blocked by Android or device restrictions.** — Restricted by standard non-root Android OS security boundaries or device hardware limits.

---

## Detailed Subsystem Audit

### 1. Security, Privacy & Crash Recovery

| Feature | Classification | Notes & Evidence |
|---|---|---|
| Global Uncaught Crash Logger (`ZehnApplication` + `CrashRecoveryManager`) | **1. Fully implemented and tested.** | Writes sanitized stack trace, timestamp, device model, and Android version to `filesDir/crash.txt`; shows full Copy/Dismiss recovery UI on relaunch. Redacts `AIza...`, `sk-...`, `Bearer ...` tokens. Verified in `ZehnCriticalFeaturesTest`. |
| Android Keystore AES-256-GCM Secret Vault (`KeystoreCryptoManager`) | **1. Fully implemented and tested.** | Encrypted storage for Gemini multi-key pool, OpenAI, Anthropic, Groq, OpenRouter, Together, Cohere, Hugging Face, and GitHub PAT. Slot add/update/delete & secret deletion verified in `ZehnV33FeaturesTest`. |
| Error Log & Header Token Redaction (`CloudLlmClient.sanitizeError`) | **1. Fully implemented and tested.** | Redacts Gemini (`AIza...`), OpenAI (`sk-...`), Groq (`gsk_...`), Hugging Face (`hf_...`), and `Bearer` tokens. Moved Gemini API key from query param `?key=` to `x-goog-api-key` header. |
| HTTPS Network Security Config (`network_security_config.xml`) | **2. Implemented but not tested.** | Enforces `cleartextTrafficPermitted="false"` globally (`usesCleartextTraffic="false"`), allowing local `127.0.0.1`/`localhost` only for local Ollama bridges. |
| Accessibility Password / OTP Field Protection (`ZehnAccessibilityService`) | **1. Fully implemented and tested.** | Masks `node.isPassword` and credential view IDs (`password`, `passcode`, `pin_input`, `pin_entry`, `otp`) as `[PROTECTED_PASSWORD]` and blocks text injection. Heuristic verified in `ZehnV33FeaturesTest`. |
| Payment & Gaming Safety Guards (`SafetyPolicyManager`) | **1. Fully implemented and tested.** | Automated checkout/payment confirmation defaults `OFF`; sensitive payment button detection and gaming screen-read block verified in `SafetyAndIntentTest`. |

### 2. Advanced AI Brain & Storage

| Feature | Classification | Notes & Evidence |
|---|---|---|
| Multi-Provider Cloud AI Client (`CloudLlmClient`: Gemini, OpenAI, Groq, OpenRouter, Hugging Face, Anthropic, Together, Cohere, Ollama) | **2. Implemented but not tested.** | Real OkHttp streaming/non-streaming HTTP requests on `Dispatchers.IO`, live `testProviderConnection` latency check, HTTP usage counters (`StateFlow<Map<AiProvider, ApiProviderUsage>>`), and provider catalog (`ProviderCatalog`). Not hit over live internet in unit tests. |
| Gemini Multi-Key Rotation & Cooldown | **1. Fully implemented and tested.** | Automatically rotates across multiple Gemini API keys on quota/429 responses and avoids cooled-down slots. Verified in `ZehnCriticalFeaturesTest`. |
| Multilingual Prompt & Language Detector (English, Urdu, Hindi, Roman Urdu / Hinglish) | **1. Fully implemented and tested.** | `LanguageDetector` classifies scripts & Roman Urdu keywords (`VoicePipelineCoordinator` & `AiEngineRouter` system instructions respond natively). Verified in `ZehnRobolectricTest` & `ZehnCriticalFeaturesTest`. |
| Room Database & Document RAG Engine (`ZehnDatabase`, `RagEngine`) | **1. Fully implemented and tested.** | Conversations, messages, long-term memories, action logs, skills, workflows, and 64-dim cosine similarity document chunks persisted in Room SQLite. Verified in `ZehnCriticalFeaturesTest`. |
| Low-RAM Guard & On-Device LiteRT-LM (`OnDeviceLiteRtManager`, `AiEngineRouter`) | **2. Implemented but not tested.** | `Mutex`-guarded model load check, < 4096 MB RAM protection, and honest missing-model fallback error reporting (`ZehnCriticalFeaturesTest` verifies offline missing-model error). Actual `.litertlm` hardware acceleration requires physical ARM64 phone. |
| Open-Source Discovery Module (`OpenSourceDiscoveryClient`) | **1. Fully implemented and tested.** | Read-only HTTPS queries to GitHub Search API and Hugging Face Models API; analyzes SPDX licenses and device RAM compatibility with zero unverified code execution. JSON parsing & RAM-fit logic verified in `ZehnV33FeaturesTest`. |

### 3. Full Voice Control & Gemini Live Audio

| Feature | Classification | Notes & Evidence |
|---|---|---|
| Voice Intent Routing & Bilingual Command Classifier (`IntentClassifier`) | **1. Fully implemented and tested.** | Routes English & Roman Urdu commands (`WhatsApp kholo`, `YouTube par ... search karo`, `Google par ...`, `Front camera open karo`, `TikTok scroll karo`, `stop`/`band karo`/`ruk jao`). Verified in `ZehnV33FeaturesTest` & `SafetyAndIntentTest`. |
| Voice Pipeline Coordinator + Emergency STOP & Barge-In | **1. Fully implemented and tested.** | Enforces Emergency STOP, supports barge-in cancellation, continuous auto-relisten toggle (`continuousConversationEnabled`), and fallback STT->LLM->TTS flow. Emergency STOP & barge-in verified in `ZehnCriticalFeaturesTest`. |
| Gemini Live Native Audio WebSocket (`GeminiLiveAudioClient`) | **1. Fully implemented and tested.** (model discovery/filtering/redaction) / **2. Implemented but not tested.** (live WebSocket PCM hardware stream) | Live models endpoint filtering (`bidiGenerateContent`), candidate fallback ordering (`gemini-3.1-flash-live-preview` -> `gemini-2.5-flash-native-audio-preview-12-2025`), custom model entry, and safe handling on missing key/mic verified in `ZehnCriticalFeaturesTest`. Live 16 kHz mic capture & 24 kHz `AudioTrack` require physical device mic/speaker. |
| Offline Wake-Word Foreground Service (`ZehnWakeWordService`, `FOREGROUND_SERVICE_MICROPHONE`) | **2. Implemented but not tested.** | Registered in `AndroidManifest.xml` with `foregroundServiceType="microphone"` and runtime `RECORD_AUDIO` permission checks before capture. |

### 4. Phone Control Agent, Camera & Call Assistant

| Feature | Classification | Notes & Evidence |
|---|---|---|
| Fast-Path App Launcher & Deep Links (`PhoneControlExecutor` + `<queries>` for 11+ apps) | **1. Fully implemented and tested.** (uninstalled package check) / **2. Implemented but not tested.** (launching installed APKs) | Covers WhatsApp, Facebook/Lite, TikTok, YouTube, Instagram, Spotify, Maps, Google Search, Chrome, Gmail, Gallery/Photos, Phone, Messages, Settings, Camera. |
| 6-Stage Accessibility GUI Loop (`Observe -> Understand -> Plan -> Execute -> Verify -> Recover`) | **1. Fully implemented and tested.** (missing-service safety & plan reporting) / **2. Implemented but not tested.** (live gestures) | Supports tap, scroll/swipe, Back, Home, Recents, and MediaStore Screenshot (`Pictures/MahiZehnAI` on API 28+). Live execution requires user enabling Zehn Accessibility Service on device. |
| CameraX Vision, Front/Back Lens, Torch, OCR/QR, Photo & Video Capture (`CameraOcrSheet`) | **2. Implemented but not tested.** | Opens visible CameraX preview dialog via user tap or voice command (`OPEN_CAMERA_UI`), supports lens flip, flash toggle, ML Kit text/QR recognition, MediaStore photo save, and video capture (checking `RECORD_AUDIO` permission). |
| Call Screening, VIP Detection, Countdown Auto-Answer & SMS Auto-Reply (`ZehnCallScreeningService`, `CallAssistantManager`) | **1. Fully implemented and tested.** (VIP matching, missing permission guard, capability report) / **2. Implemented but not tested.** (live cellular ring events) | `CallScreeningService` (API 29+) + `TelephonyCallback`/`PhoneStateListener` fallback, `ContactsContract.PhoneLookup`, configurable delay auto-answer, VIP pass-through, SMS rejection reply, and history log. |
| VoIP / SIP Receptionist Configuration (`PreferencesRepository` + `CallAssistantScreen`) | **3. Partially implemented.** | Persists VoIP/SIP mode toggle and SIP server URI (`voipSipModeEnabled`, `voipSipServerUri`) for external SIP bridge coordination; full native PJSIP/WebRTC SIP media stack is **5. Missing.** (not bundled). |
| GitHub Auto-Dev Mode (`GitAutoDevManager`) | **2. Implemented but not tested.** | WorkManager-backed autonomous branch (`zehn-auto`), secret diff scanner, Contents/Git Data API commit, Actions polling, and PR creation. Requires live GitHub PAT & repository setup. |

### 5. Features Blocked by Android OS or Device Restrictions

| Capability | Classification | Technical Reason & Implemented Alternative |
|---|---|---|
| Injecting TTS/AI Voice Directly into Cellular Call Uplink Microphone | **6. Blocked by Android or device restrictions.** | Standard third-party non-root Android apps cannot route arbitrary audio buffers into the modem telephony uplink stream (`AudioManager.MODE_IN_CALL`). `CallCapabilityReport.canInjectAudioIntoCellularUplink` is explicitly `false`; Zehn provides local call announcement, auto-answer/reject, SMS auto-reply, and VoIP/SIP config instead. |
| Force-Stopping Arbitrary Third-Party Apps Programmatically | **6. Blocked by Android or device restrictions.** | Android restricts `forceStopPackage` to system/signature apps (`FORCE_STOP_PACKAGES`). Calling `"close app"` / `"app band karo"` honestly performs `AccessibilityService` Home / app minimize and informs the user. |
| Silent Background Camera/Mic Activation Without Notice | **6. Blocked by Android or device restrictions.** | Android 11+ & Google Play policies require visible UI or foreground service indicators. Camera voice commands open visible `CameraVisionDialog` or system Camera UI; mic runs inside `FOREGROUND_SERVICE_MICROPHONE`. |
| Unbounded Multi-Billion-Parameter Local Models on Low-RAM Phones | **6. Blocked by Android or device restrictions.** | OS Low Memory Killer terminates native allocations exceeding device RAM. `AiEngineRouter` and `OpenSourceDiscoveryClient` enforce `< 4096 MB` RAM thresholds and guide users toward Cloud API routing. |

### 6. UI Placeholders Check

| Check | Classification | Verification |
|---|---|---|
| Dead Buttons / Stub Screens | **4. UI placeholder only.** *(Count: 0)* | Every card in `HubScreen`, every quick-action chip in `MissionModeScreen`, all provider slots + Test/Delete buttons in `ApiKeysScreen`, `CallAssistantScreen`, `OpenSourceDiscoveryScreen`, and `DiagnosticsScreen` are wired through `ZehnViewModel` to live repositories/services. |
