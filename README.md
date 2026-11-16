# MAHI / Zehn AI V3.3 (`com.zehn.assistant`)

**Mahi / Zehn AI V3.3** is a privacy-first, bilingual Android voice assistant and phone-automation agent built with **Kotlin**, **Jetpack Compose (Material 3)**, **MVVM + Hilt**, **Room**, **Coroutines/Flow**, **OkHttp/Retrofit**, **CameraX + ML Kit**, and **Google LiteRT-LM** (`minSdk 24`, `targetSdk 35`).

> All existing `com.zehn.assistant` identifiers, Room database schemas, encrypted Keystore preferences, and Clay/Glass Material 3 UI are preserved and upgraded for V3.3.

---

## Core V3.3 Capabilities

- **Advanced Multi-Provider AI Brain & Encrypted Vault**:
  - Supports **Gemini** (header-based `x-goog-api-key` auth + multi-key quota rotation), **OpenAI-compatible**, **Anthropic**, **Groq**, **OpenRouter**, **Hugging Face Inference Router**, **Together**, **Cohere**, **Ollama**, and on-device **LiteRT-LM** (`Gemma 4 E2B / E4B`, `Qwen3 0.6B`) with `< 4096 MB` low-RAM OOM protection.
  - Real per-provider connection test probes (`latency` + HTTP diagnostics), live API usage counters, Add/Edit/Delete key slots, and hardware-backed **Android Keystore AES-256-GCM** storage (zero hardcoded keys, token redaction in error logs & crash reports).
  - **Open-Source Discovery**: Read-only search across GitHub Search API and Hugging Face Models API with SPDX license checks and device RAM compatibility hints.
- **Bilingual Voice Control & Gemini Live Audio**:
  - Understands **English**, **Urdu (`ur`)**, **Hindi (`hi`)**, and **Roman Urdu / Hinglish** (`"WhatsApp kholo"`, `"YouTube par lofi search karo"`, `"Google par weather search karo"`, `"Front camera open karo"`, `"band karo"`).
  - Supports **Gemini Live WebSocket native audio** (16 kHz mic PCM + 24 kHz `AudioTrack` playback, barge-in, tool calling, live `/models` `bidiGenerateContent` filtering) with automatic **STT -> Streaming LLM -> 8-Voice TTS** fallback and opt-in **Continuous Voice Conversation** mode.
- **6-Stage Accessibility Agent & App Launchers**:
  - Fast-path intents + `<queries>` visibility for 11+ apps (WhatsApp, Facebook/Lite, TikTok, YouTube, Instagram, Spotify, Maps, Google Search, Chrome, Gmail, Gallery/Photos, Dialer, Messages, Settings, Camera).
  - Accessibility Service (`Observe -> Understand -> Plan -> Execute -> Verify -> Recover`) with tap, swipe/scroll, Back, Home/minimize, Recents, MediaStore screenshots, **password/PIN/OTP masking**, and default-OFF **Payment Confirmation** & **Gaming Screen** safety guards.
- **CameraX Vision, Call Assistant & Diagnostics**:
  - Live `CameraVisionDialog` with Front/Back lens toggle, Torch toggle, MediaStore Photo + Video recording (`RECORD_AUDIO` gated), and ML Kit OCR/QR scan.
  - `ZehnCallScreeningService` (API 29+) + `CallAssistantManager` with `ContactsContract` lookup, VIP bypass list, configurable countdown auto-answer, SMS reject reply, and transparent `CallCapabilityReport`.
  - Global uncaught crash logger (`filesDir/crash.txt`) with sanitized Copy/Dismiss recovery screen and **GitHub Auto-Dev** WorkManager agent.

See [`FEATURE_STATUS.md`](./FEATURE_STATUS.md) for the honest 6-tier verification matrix and [`CHANGELOG.md`](./CHANGELOG.md) for full V3.3 notes.

---

## Verified Local Build & Test Commands

- **AI Studio / system Gradle container**:
  ```bash
  gradle :app:testDebugUnitTest --stacktrace
  gradle assembleDebug
  ```
- **Local Android Studio / Git checkout (with Gradle wrapper)**:
  ```bash
  ./gradlew clean testDebugUnitTest assembleDebug --stacktrace
  ```

---

## GitHub Actions CI/CD (`.github/workflows/android-build.yml`)

- **Triggers**: Pushes & PRs to `main`, `v*` release tags, and manual `workflow_dispatch`.
- **Pipeline**:
  1. Sets up JDK 17, Android SDK licenses, and Gradle `9.3.1` (wrapper-independent with `./gradlew` fallback).
  2. Executes `testDebugUnitTest` + `assembleDebug` and validates non-empty APK output + SHA-256.
  3. Uploads test reports (`mahi-zehn-test-reports`) and Debug APK (`mahi-zehn-debug-apk`).
  4. When release signing secrets (`RELEASE_KEYSTORE_BASE64` or `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_PASSWORD`) are configured, builds & uploads the signed Release APK (`mahi-zehn-release-apk`).
  5. Publishes a GitHub Release with attached APKs only when triggered by a `v*` tag on a green build (`overwrite_files: false`).

---

## Important Android Platform Boundaries

- **Cellular Call Uplink Audio**: Standard non-root Android apps cannot inject synthetic audio directly into the cellular call uplink microphone; Zehn honestly reports this in **Call Assistant** and focuses on local announcement, auto-answer/reject, SMS replies, and VoIP/SIP URI settings.
- **Force-Closing Apps**: Non-root Android apps cannot force-stop other foreground apps; close commands cleanly minimize to the Home screen.
- **Sensitive Accessibility Data**: Password, PIN, and OTP view contents are automatically masked (`[PROTECTED_PASSWORD]`).
