# CHANGELOG — MAHI / Zehn AI V3.3

All changes below were built on top of the existing `com.zehn.assistant` project, preserving `applicationId`, package structure, Room schema continuity, Android Keystore vault compatibility, and Material 3 Clay/Glass UI.

---

## V3.3 — Autonomous Android Assistant Upgrade

### Security & Stability Fixes
- **Strict HTTPS Network Policy**: Replaced `usesCleartextTraffic="true"` with `usesCleartextTraffic="false"` and `res/xml/network_security_config.xml`, restricting cleartext traffic solely to local loopback (`127.0.0.1`, `localhost`).
- **Gemini Header Authentication**: Moved Gemini API keys out of URL query strings (`?key=`) into the `x-goog-api-key` HTTP header across chat, vision, and Imagen calls.
- **Expanded Token Sanitization**: Added `gsk_` (Groq) alongside `AIza`, `sk-`, `hf_`, and `Bearer` redaction in `CloudLlmClient.sanitizeError` and `CrashRecoveryManager`.
- **Accessibility Password & OTP Guard**: Blocked reading or injecting text into password, passcode, PIN, and OTP fields inside `ZehnAccessibilityService`.
- **Low-RAM On-Device Model Protection**: Guarded on-device model execution with a `Mutex` and `4096 MB` device RAM check in `AiEngineRouter`, preventing out-of-memory crashes on low-RAM handsets.
- **Release Debuggability Hardening**: Explicitly set `isDebuggable = false` in the Gradle `release` build type.

### Advanced AI Brain & Discovery
- **Hugging Face Provider Support**: Added `AiProvider.HUGGINGFACE` with OpenAI-compatible router inference (`Qwen2.5-72B-Instruct`, `Llama-3.3-70B-Instruct`, `gemma-3-27b-it`) and encrypted `KEY_HF_TOKEN` storage.
- **Live Provider Testing & Usage Telemetry**: Added real per-provider `testProviderConnection` HTTP probes measuring latency/status and live `providerUsage` counters (`requests`, `successes`, `failures`, `lastLatencyMs`).
- **Full Key Slot Management**: Added single-slot Add, Update, Test, and Delete controls for Gemini API key rotation pool and all other provider keys in `KeystoreCryptoManager` & `ApiKeysScreen`.
- **Safe Open-Source Discovery (`OpenSourceDiscoveryClient`)**: Added read-only GitHub repository and Hugging Face model discovery with SPDX license assessment and device RAM compatibility labels.

### Bilingual Voice Control & Automation Agent
- **English / Urdu / Hindi / Roman Urdu Expansion**: Added bilingual intent classification (`"WhatsApp kholo"`, `"YouTube par ... search karo"`, `"Google par ... search karo"`, `"Front camera open karo"`, `"TikTok scroll karo"`, `"stop"` / `"band karo"` / `"ruk jao"`).
- **Continuous Conversation Loop**: Added opt-in `continuousConversationEnabled` auto-relisten after TTS replies plus bounded transient STT retries and immediate voice stop detection in `VoicePipelineCoordinator`.
- **11+ App Package Queries & Agent Shortcuts**: Expanded `<queries>` and `PhoneControlExecutor` aliases for WhatsApp, Facebook/Lite, TikTok, YouTube, Instagram, Spotify, Google Maps, Google Search, Chrome, Gmail, Gallery/Photos, Dialer, Messages, Settings, and Camera.
- **6-Stage Accessibility Loop & Gesture Extensions**: Upgraded `AgentLoop` (`Observe -> Understand -> Plan -> Execute -> Verify -> Recover`) with `performSwipe`, `performRecents`, and `saveScreenshotToGallery` (`Pictures/MahiZehnAI` on API 28+).

### CameraX Vision & Call Assistant
- **Full Camera Vision Dialog (`CameraOcrSheet`)**: Added Front/Back camera toggle, Flash/Torch toggle, MediaStore Photo capture, MediaStore Video recording (with `RECORD_AUDIO` check), visible status pill, and ML Kit OCR/QR scanning.
- **Call Screening & Auto-Answer (`ZehnCallScreeningService`, `CallAssistantManager`)**: Added Android 10+ `CallScreeningService`, VIP caller detection (by contact name or trailing phone digits), configurable countdown auto-answer timer, SMS auto-reply on reject, call interaction log, and honest `CallCapabilityReport`.
- **New Screens (`CallAndDiscoveryScreens.kt`)**: Added Call Assistant console, Open-Source Discovery browser, and real-time System Diagnostics screen in Hub navigation.

### CI/CD & Testing
- **Automated CI/CD (`.github/workflows/android-build.yml`)**: Runs JDK 17 + unit/Robolectric tests (`testDebugUnitTest`), debug APK build & size verification, optional keystore-signed release build, artifact uploads, and tag-triggered GitHub Releases.
- **Expanded Robolectric Verification**: Added `ZehnV33FeaturesTest` bringing total passing automated JVM/Robolectric tests to **19 / 19**.

---

## Known Android OS & Hardware Limitations
- **Cellular Uplink Audio Injection**: Standard non-root Android apps cannot inject assistant TTS audio directly into the modem call uplink microphone; documented transparently in `CallCapabilityReport` and UI.
- **Force-Closing Other Apps**: Android prohibits third-party apps from force-stopping foreground packages; app-close voice actions safely minimize to Home via Accessibility Service.
- **Headless Hardware Limits**: Live Gemini WebSocket audio, physical CameraX lenses, and live telephony screening require a real Android device for end-to-end manual QA.
