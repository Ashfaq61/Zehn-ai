# Zehn AI V4.0 (`com.zehn.assistant`)

**Zehn AI V4.0** is an autonomous, privacy-first Android AI assistant featuring continuous GitHub & Hugging Face research discovery, license & security-gated feature staging on isolated GitHub branches, automated GitHub Actions APK building, and verified release update notifications—built on top of Zehn's existing bilingual voice assistant, 6-stage Accessibility automation agent, CameraX vision, Call Screening, and LiteRT-LM on-device engine (`minSdk 24`, `targetSdk 35`).

---

## V4.0 Highlights

- **Autonomous Upgrade Center (`upgrade_center` in Command Hub)**:
  - Live telemetry dashboard: GitHub connection status, research cycle stage, last/next scan schedule, discovered tools, approved & integrated counts, CI build pass count, and latest APK release.
  - Complete controls: `Start Research`, `Stop Research`, `Scan GitHub`, `Scan Hugging Face`, `Review New Features`, `Approve Integration`, `Reject Integration`, `Build APK`, `Check Updates`, and `Restore Previous Version`.
- **GitHub Research & Hugging Face Discovery (`AutonomousResearchAgent`)**:
  - Queries official GitHub REST & Hugging Face APIs across Android AI, voice/STT/TTS, AccessibilityService, screen automation, call/Telecom, MCP, RAG, OCR/camera, offline LLM, and security/performance domains.
  - Inspects READMEs, `build.gradle.kts`, licenses, `minSdk`, RAM/storage/battery footprint, and duplicates—persisting history in Room (`discovered_tools`) so cached repos are skipped on re-scans.
- **GitHub Auto-Dev & Safety Gate (`GitHubAutoDevEngine`, `RepoSafetyAnalyzer`)**:
  - Stores a fine-grained GitHub PAT exclusively in **Android Keystore AES-256-GCM** (masked UI, redacted logs).
  - Never commits directly to `main`; creates isolated `zehn-auto/...` branches via Git Data API, scans every diff for leaked secrets/malware/DCL/workflow edits, opens PRs, polls GitHub Actions builds (with bounded failed-log diagnosis), enforces approval before merging sensitive permission/accessibility/call/security edits, and supports 1-tap rollback to stable commit SHAs.
- **Verified APK Build & Update Notifications (`ReleaseUpdateManager`, `.github/workflows/android-build.yml`)**:
  - WorkManager schedules (`Daily`, `Weekly`, `Manual` with `Pause`/`Resume`) respecting battery & network constraints.
  - GitHub Actions runs `testDebugUnitTest`, `lintDebug`, dependency checks, `assembleDebug` + `aapt2` validation, optional keystore-signed `assembleRelease`, and tag-triggered releases (`overwrite_files: false`).
  - `ReleaseUpdateManager` checks compatibility, verifies trusted GitHub download URLs, alerts via Android notifications (`POST_NOTIFICATIONS` aware), and opens Android's standard installation confirmation flow (never bypassing OS installer security).

*(All V1.0–V3.3 bilingual voice, Gemini Live audio, multi-provider AI vault, CameraX photo/video/OCR, Call Screening, and 6-stage Accessibility agent features are preserved intact.)*

See [`FEATURE_STATUS.md`](./FEATURE_STATUS.md), [`AUTONOMOUS_UPGRADE_REPORT.md`](./AUTONOMOUS_UPGRADE_REPORT.md), and [`CHANGELOG.md`](./CHANGELOG.md).

---

## Build & Test Commands

- **AI Studio / system Gradle container**:
  ```bash
  gradle :app:testDebugUnitTest --console=plain
  gradle assembleDebug
  ```
- **Local Android Studio / Git checkout**:
  ```bash
  ./gradlew clean testDebugUnitTest lintDebug assembleDebug --stacktrace
  ```
