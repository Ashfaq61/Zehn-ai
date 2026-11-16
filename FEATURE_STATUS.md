# MAHI / Zehn AI V4.0 — Complete Feature Audit (`FEATURE_STATUS.md`)

**Package:** `com.zehn.assistant` | **Version:** `4.0.0` (`versionCode = 4`) | **Target SDK:** 35 | **Min SDK:** 24  
**Verified Automated Checks:**
- `compile_applet` (Debug Kotlin + KSP + Hilt + Manifest + Resources): **PASSED**
- `gradle :app:testDebugUnitTest --console=plain`: **23 / 23 tests PASSED** (0 failures, 0 skipped) across `ExampleUnitTest` (1), `SafetyAndIntentTest` (3), `ZehnRobolectricTest` (2), `ZehnCriticalFeaturesTest` (7), `ZehnV33FeaturesTest` (6), and `ZehnV40AutonomousUpgradeTest` (4).

> Hardware-dependent features (live mic PCM streaming, physical CameraX lenses, live external APIs, GitHub Actions runner execution, and on-device Accessibility gestures) require a physical Android device or connected GitHub repository credentials and are strictly classified as `2. Implemented but not tested.` or `6. Blocked by Android or device restrictions.`

---

## Classification Key

1. **1. Fully implemented and tested.** — Implemented in production code and verified in Robolectric/JUnit unit tests during this build.
2. **2. Implemented but not tested.** — Compiled cleanly and wired end-to-end in Kotlin/Compose/Manifest, requiring physical Android hardware or live external credentials/CI runner.
3. **3. Partially implemented.** — Configuration/bridge hooks present; external runtime component intentionally out-of-bundle.
4. **4. UI placeholder only.** — Zero in V4.0.
5. **5. Missing.** — Capability deliberately not bundled.
6. **6. Blocked by Android or device restrictions.** — Restricted by standard non-root Android security boundaries.

---

## Detailed Subsystem Audit

### 1. Autonomous GitHub Research, Self-Upgrade & APK Build Engine (V4.0)

| Feature | Classification | Notes & Evidence |
|---|---|---|
| Safety, License, Security & Duplicate Analyzer (`RepoSafetyAnalyzer`) | **1. Fully implemented and tested.** | Verifies SPDX licenses (`MIT`/`Apache-2.0`/`BSD` vs `GPL`/`AGPL`), extracts Gradle `minSdk` & dependencies, detects overlaps against Zehn built-in capabilities, blocks leaked secrets/DCL/malware diffs, and enforces sensitive-change approval gates. Verified in `ZehnV40AutonomousUpgradeTest`. |
| Autonomous Research Agent (`AutonomousResearchAgent`) | **1. Fully implemented and tested.** *(parsing, inspection, Room dedup)* / **2. Implemented but not tested.** *(live internet scan)* | Covers 14 GitHub topic streams + 6 Hugging Face tracks, decodes README & build scripts, stores findings in Room `discovered_tools` (v2 migration) to skip re-evaluating cached repos, and generates modular adapter proposals. |
| GitHub Auto-Dev Engine (`GitHubAutoDevEngine`) | **1. Fully implemented and tested.** *(scripted REST/Git Data API & rollback)* / **2. Implemented but not tested.** *(live remote repo)* | Encrypted Keystore PAT auth check, isolated `zehn-auto/...` branch creation, atomic blob->tree->commit Git Data API push, Pull Request creation, Actions workflow polling + failed job log tail + bounded LLM repair attempts, approval-gated merge, and rollback to saved SHA. |
| Release & Update Notification Manager (`ReleaseUpdateManager`) | **1. Fully implemented and tested.** *(release parsing, semver, trusted origin)* / **2. Implemented but not tested.** *(live tray notification + APK download)* | Polls GitHub Releases API, validates device `minSdk` and trusted `github.com`/`githubusercontent.com` URLs, records rollback release history in Room, posts `CHANNEL_UPDATES` notification (checking `POST_NOTIFICATIONS`), and opens verified download URL for normal Android install confirmation. |
| Background Research & Update Scheduler (`UpgradeScheduler`, `ResearchScheduleWorker`, `ReleaseCheckWorker`) | **2. Implemented but not tested.** | WorkManager periodic Daily/Weekly/Manual schedules + 12h release watcher with `NetworkType.CONNECTED` and `setRequiresBatteryNotLow(true)` plus pause/resume controls. |
| Autonomous Upgrade Center UI (`AutonomousUpgradeCenterScreen`) | **2. Implemented but not tested.** *(compiled & routed cleanly)* | Displays live metrics grid, encrypted GitHub PAT test/save/delete, all 10 required controls (`Start/Stop Research`, `Scan GitHub/HF`, `Review`, `Approve`, `Reject`, `Build APK`, `Check Updates`, `Restore Previous Version`), proposals, discovered tools, releases, and audit logs. |
| GitHub Actions CI/CD Build & Release Workflow (`.github/workflows/android-build.yml`) | **2. Implemented but not tested.** *(workflow YAML updated; runs on GitHub Actions push/tag/dispatch)* | Triggers on `main`, `zehn-auto/**`, `v*` tags, `workflow_dispatch`; runs unit tests, `lintDebug`, `releaseRuntimeClasspath` deps dump, `assembleDebug` + `aapt2` verification, optional signed `assembleRelease`, unique `versionCode`, artifact upload, and safe tag release (`overwrite_files: false`). |

### 2. Core AI, Security, Voice, Camera & Call Systems (V1.0–V3.3 Preserved)

| Feature | Classification | Notes & Evidence |
|---|---|---|
| Global Crash Logger & Recovery (`ZehnApplication`, `CrashRecoveryManager`) | **1. Fully implemented and tested.** | Writes `filesDir/crash.txt` on uncaught exceptions, redacts all API & GitHub tokens, displays Copy/Clear recovery UI. Verified in `ZehnCriticalFeaturesTest`. |
| Keystore AES-256-GCM Secret Vault (`KeystoreCryptoManager`) | **1. Fully implemented and tested.** | Encrypted storage for Gemini multi-key pool, OpenAI, Anthropic, Groq, OpenRouter, Hugging Face, Together, Cohere, and GitHub PAT. |
| Multi-Provider AI Brain & Room RAG (`CloudLlmClient`, `AiEngineRouter`, `RagEngine`) | **1. Fully implemented and tested.** *(Room RAG, Gemini rotation, redaction, offline missing-model error)* / **2. Implemented but not tested.** *(live external LLM HTTP & native LiteRT-LM)* | Supports Gemini (`x-goog-api-key`), OpenAI, Groq, OpenRouter, Hugging Face, Anthropic, Together, Cohere, Ollama, and LiteRT-LM (`< 4096 MB` RAM guard). |
| Bilingual Voice Control, Gemini Live Audio & Continuous Mode | **1. Fully implemented and tested.** *(LanguageDetector, IntentClassifier, Emergency STOP, barge-in, Live model filtering)* / **2. Implemented but not tested.** *(live AudioRecord/AudioTrack hardware stream)* | English, Urdu, Hindi, and Roman Urdu / Hinglish intent classification + WebSocket Live audio with automatic STT/LLM/TTS fallback. |
| Accessibility GUI Agent, 11+ App Launchers & Password Guard | **1. Fully implemented and tested.** *(password/OTP node mask, uninstalled check, missing service safety)* / **2. Implemented but not tested.** *(live device taps/swipes/screenshots)* | 6-stage `Observe -> Understand -> Plan -> Execute -> Verify -> Recover` loop with swipe, recents, and MediaStore screenshots. |
| CameraX Vision, Front/Back, Torch, Photo/Video & OCR/QR | **2. Implemented but not tested.** | Visible `CameraVisionDialog` with lens switch, torch, MediaStore capture, and ML Kit recognition. |
| Call Screening, VIP Bypass, Countdown Auto-Answer & SMS Auto-Reply | **1. Fully implemented and tested.** *(VIP match, missing permission guard, uplink report)* / **2. Implemented but not tested.** *(live telephony calls)* | `ZehnCallScreeningService` (API 29+) + `TelephonyCallback`/`PhoneStateListener`. |
| VoIP / SIP Bridge Config (`CallAssistantScreen`) | **3. Partially implemented.** (Config UI/Prefs) / **5. Missing.** (Native PJSIP/WebRTC media stack) | Stores SIP URI for external bridges; keeps bundle compact and Play-compliant. |

### 3. Blocked by Android OS or Device Restrictions

| Capability | Classification | Technical Reason & Implemented Alternative |
|---|---|---|
| Self-Rewriting Installed APK Binary / Silent APK Install | **6. Blocked by Android or device restrictions.** | Android sandboxes prohibit a running APK from modifying its signed APK or silently installing packages. Zehn commits changes exclusively through its connected GitHub repo, builds via GitHub Actions, notifies on verified Release, and launches Android's normal installation flow. |
| Injecting TTS Audio Directly into Cellular Call Uplink Microphone | **6. Blocked by Android or device restrictions.** | Blocked for third-party non-root apps by Android Telecom (`CallCapabilityReport.canInjectAudioIntoCellularUplink = false`). |
| Force-Stopping Arbitrary Third-Party Apps | **6. Blocked by Android or device restrictions.** | Android restricts `FORCE_STOP_PACKAGES` to system/signature apps; close actions minimize via Home / Accessibility. |
| Silent Background Camera/Mic Capture Without Indicator | **6. Blocked by Android or device restrictions.** | Blocked by Android 11+ & Google Play privacy rules; requires visible UI or `FOREGROUND_SERVICE_MICROPHONE`. |
