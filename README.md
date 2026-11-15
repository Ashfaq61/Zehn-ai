# Zehn AI (`com.zehn.assistant`)

**Zehn AI** is a privacy-first Android voice assistant and autonomous phone-automation agent built with Kotlin, Jetpack Compose (Material 3), MVVM, Hilt, Room, Coroutines/Flow, OkHttp/Retrofit, CameraX + ML Kit, and Google LiteRT-LM (`minSdk 24`, `targetSdk 35`, 32-bit & 64-bit).

---

## Key Capabilities

- **Modular Voice Pipeline**: Silero-style acoustic VAD -> Swappable STT (`SpeechRecognizer`, Whisper-compatible, offline fallback) -> Streaming LLM (sentence-chunked) -> TTS with **8 voices** (4 female, 4 male), barge-in interruption, offline `"Hey Zehn"` wake-word foreground service, `VoiceInteractionService` (default system assistant), Realtime WebSocket mode, and Webhook mode.
- **Multilingual Auto-Detection**: English (`en`), Urdu (`ur`), Hindi (`hi`), and Hinglish / Roman Urdu.
- **Multi-Provider AI Engine & On-Device Gemma 4**:
  - Cloud & local providers: Gemini (multi-key auto-rotation on quota errors), OpenAI-compatible, Anthropic/Claude, Groq, OpenRouter, Together, Cohere, and Ollama.
  - On-device **LiteRT-LM** (`Gemma 4 E2B Q4`, `Gemma 4 E4B`, `Qwen3 0.6B`) with Hugging Face resumable downloads, RAM & storage checks, GPU/CPU Auto-Tuner, and Cloud / Hybrid / Offline routing.
  - On-device **RAG Projects** (PDF/TXT/Image OCR chunking + 64-dim embeddings in Room/SQLite with citations), live CameraX + ML Kit OCR/QR scanning, photo vision analysis, and toggle-gated image generation.
- **Phone Automation Agent**:
  - `AgentLoop`, `PlanManager`, `IntentClassifier`, `VisionEngine` with Fast Path (Direct Intent/DeepLink) & Standard Path (`AccessibilityService` GUI observe-act-reflect loop).
  - User-editable `SKILL.md` skills, JSON Workflows, fixed YAML Flows with task recording & zero-LLM replay, Triggers, Call Assistant, Notification Listener, Floating Bubble, Quick Settings Tile, and Home Widget.
- **4-Tier Memory, Encrypted Vault & Crash Recovery**:
  - Working, Session, Long-Term, and Notification Intelligence tiers with "Forget" controls and JSON backup export/import.
  - Hardware-backed **Android Keystore AES-256-GCM** encrypted secret vault (zero hardcoded keys, zero analytics).
  - Default-OFF **Automated Payment Confirmation**, **Gaming Assistant Screen Check**, **Image Generation** toggle, app block-list, step limit, Emergency STOP, Action Log, Samsung OneUI guidance, and sanitized **Crash Recovery** on restart.

---

## 1. Google AI Studio -> GitHub Export & Push Workflow

1. **Export / Push from Google AI Studio**:
   - Use the **Export / Push to GitHub** option in Google AI Studio settings, **or** export the workspace ZIP, extract it locally, and push cleanly via Git:
     ```bash
     git init
     git branch -M main
     git add .
     git commit -m "Initial Zehn AI v1.0.0"
     git remote add origin https://github.com/<YOUR_USERNAME>/<YOUR_REPO>.git
     git push -u origin main
     ```
2. **Security Rules**:
   - **Never** commit GitHub Personal Access Tokens (PATs), `.env` files, or `.jks`/`.keystore` files into the repository (already excluded in `.gitignore`).
   - Use GitHub's secure OAuth/SSH authentication or GitHub Actions Secrets.

---

## 2. GitHub Actions APK Build & Signed Release Setup

Workflow file: `.github/workflows/android-build.yml`

- **Automatic Builds**: Triggers on every push to `main`, pull requests to `main`, `v*` release tags, and manual `workflow_dispatch`.
- **Pipeline Steps**:
  1. Sets up JDK 17, Android SDK, and Gradle `9.3.1` (bootstraps `./gradlew` if absent).
  2. Runs `./gradlew :app:dependencies` and `./gradlew clean test assembleDebug`.
  3. Validates `app/build/outputs/apk/debug/app-debug.apk` (`aapt2 dump badging` confirms package `com.zehn.assistant`, `minSdk 24`, `targetSdk 35`, non-zero size, and SHA-256).
  4. Builds a **Signed Release APK** (`./gradlew assembleRelease`) whenever signing secrets are set.
  5. Uploads **Debug APK**, **Unit & Robolectric Test Reports**, and **Signed Release APK** as GitHub Actions Artifacts (`if-no-files-found: error`).
  6. Creates a **GitHub Release** with auto-generated changelog (`overwrite_files: false` to preserve prior stable releases) when a tag like `v1.0.0` is pushed.

### Optional Release Signing Secrets (`Settings -> Secrets and variables -> Actions`)

Create an upload keystore locally (`keytool -genkeypair -v -keystore my-upload-key.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000`) and add these repository secrets:

| Secret Name | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | Base64 output of `base64 -w0 my-upload-key.jks` |
| `STORE_PASSWORD` | Keystore password |
| `KEY_PASSWORD` | Key password (for alias `upload`) |

> Keep the same keystore for all future updates so Android allows in-place upgrades over `com.zehn.assistant`.

---

## 3. Versioning, Safe Updates & Rollback

- Initial release starts at `versionName = "1.0.0"`, `versionCode = 1` in `app/build.gradle.kts`.
- For every new release, increment `versionCode` by `+1`, bump `versionName` (e.g., `1.0.1`), commit to `main`, and push a matching tag:
  ```bash
  git tag v1.0.0
  git push origin v1.0.0
  ```
- Previous GitHub Releases and their attached APKs remain untouched (`overwrite_files: false`) for instant rollback.

---

## 4. Local Build & Test Commands

- In **Android Studio / local machine**:
  ```bash
  ./gradlew clean test assembleDebug
  ```
- In **AI Studio / system-Gradle container**:
  ```bash
  gradle clean test assembleDebug
  ```
- Output APK location:
  - Debug: `app/build/outputs/apk/debug/app-debug.apk`
  - Signed Release: `app/build/outputs/apk/release/app-release.apk`

## 5. Installing on Android

1. Download `app-debug.apk` (or `app-release.apk`) from **GitHub Actions -> Workflow Run -> Artifacts** or **GitHub Releases**.
2. Transfer to your Android 7.0+ (API 24+) phone and tap to install (enable **Install unknown apps** when prompted).
3. Inside Zehn AI:
   - Open **API Keys** to add encrypted provider keys (or download Gemma 4 in **Voice & AI Models** for offline inference).
   - Open **Permissions** & **Touch Guard** to enable Microphone, Camera, Accessibility Service, Notification Access, Floating Overlay, and Default System Assistant.
