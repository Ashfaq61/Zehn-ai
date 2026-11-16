# CHANGELOG — Zehn AI (`com.zehn.assistant`)

## V4.0 — Autonomous GitHub Research, Self-Upgrade & APK Build Engine

### Added
- **Autonomous GitHub & Hugging Face Research Agent (`AutonomousResearchAgent`)**:
  - Covers 14 GitHub capability domains (Android AI assistants, voice, AccessibilityService, screen/computer-use, AI call/Telecom/InCallService, MCP tools, agent frameworks, RAG/memory, vision/OCR, STT/TTS, offline LLMs, UI automation, security/performance) plus 6 Hugging Face model tracks (Urdu ASR, multilingual TTS, lightweight LLMs, vision/OCR, LiteRT/on-device).
  - Decodes repository README & Gradle/package specs to extract actual `minSdk`, dependencies, licenses, RAM/storage/battery estimates, and multi-factor compatibility scores without judging only by star counts.
  - Persists evaluated tools in Room `discovered_tools` (`ZehnDatabase` v2 with safe `MIGRATION_1_2`) so repositories are not repeatedly re-evaluated.
- **Safety, License, Duplicate & Approval Analyzer (`RepoSafetyAnalyzer`)**:
  - Audits SPDX licenses, scans diffs for leaked tokens (`ghp_`, `github_pat_`, `AIza`, `sk-`, `gsk_`, `hf_`), blocks external DCL/malware/oversized files/workflow edits (`allowWorkflowEdits` default OFF), detects duplicate built-in capabilities, and gates sensitive permissions/accessibility/call/security/manifest changes behind user approval.
- **GitHub Auto-Dev Integration Engine (`GitHubAutoDevEngine`)**:
  - Securely authenticates using encrypted Keystore PAT (`KEY_GITHUB_TOKEN`, never embedded or logged), monitors repo permissions, creates isolated `zehn-auto/...` branches (never committing directly to `main`), performs atomic Git Data API commits, opens PRs, polls GitHub Actions builds + downloads failed job log tails for bounded self-repair diagnostics (max 5 attempts), supports approval-gated merges, and rolls back branches to stable commit SHAs.
- **Release & Update Notification Manager (`ReleaseUpdateManager`)**:
  - Monitors published GitHub Releases, verifies device compatibility & trusted GitHub URLs, records release history for rollback, requests `POST_NOTIFICATIONS` on Android 13+, posts `CHANNEL_UPDATES` notifications with version/changelog/APK size, and launches the verified download URL so the user completes Android's standard installation confirmation.
- **Autonomous Scheduler (`UpgradeScheduler`, `ResearchScheduleWorker`, `ReleaseCheckWorker`)**:
  - Supports configurable `DAILY`, `WEEKLY`, and `MANUAL` schedules plus `SECURITY_ONLY` / `ALL_AI_TOOLS` focus filters, pause/resume controls, battery-not-low & connected-network constraints, and periodic release checks via WorkManager.
- **Autonomous Upgrade Center (`AutonomousUpgradeCenterScreen`)**:
  - Added full control dashboard in Hub (`upgrade_center`) displaying live connection status, research metrics, last/next scan dates, discovered/approved/integrated/passed-CI counts, latest APK version, GitHub configuration, all 10 control buttons, proposals review queue, release history, rollback button, and local API audit log.
- **Upgraded CI/CD (`.github/workflows/android-build.yml`)**:
  - Added `zehn-auto/**` branch CI triggers, `lintDebug`, `:app:dependencies` audit, `aapt2 dump badging` package validation (`com.zehn.assistant`), dynamic `ZEHN_VERSION_CODE` / `ZEHN_VERSION_NAME`, build/test/lint artifact archival, optional signed `assembleRelease`, and safe GitHub Release publishing (`overwrite_files: false`).
- **Acceptance Test Suite (`ZehnV40AutonomousUpgradeTest`)**:
  - Added 4 end-to-end Robolectric + stateful OkHttp interceptor acceptance tests; total automated tests passing increased to **23 / 23**.

---

## V3.3 — Autonomous Android Assistant Upgrade
*(Preserved in full: HTTPS network security config, Gemini `x-goog-api-key` headers, Hugging Face Inference Router, key-slot CRUD + latency/usage metrics, bilingual English/Urdu/Hindi/Roman Urdu voice commands, Continuous Conversation mode, CameraX photo/video/flash/lens flip, Call Screening + VIP filter + Auto-Answer + CallCapabilityReport, Open-Source Discovery UI, and Honest Diagnostics.)*
