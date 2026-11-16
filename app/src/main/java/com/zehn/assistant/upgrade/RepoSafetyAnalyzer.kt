package com.zehn.assistant.upgrade

import com.zehn.assistant.data.db.DiscoveredToolEntity
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class LicenseEvaluation(
    val normalizedLicense: String,
    val status: String, // COMPATIBLE, REVIEW, INCOMPATIBLE
    val note: String
)

data class SecurityScanResult(
    val clean: Boolean,
    val blockingReasons: List<String>,
    val warnings: List<String>
)

data class IntegrationRiskAssessment(
    val riskLevel: String, // LOW, SENSITIVE, BLOCKED
    val requiresApproval: Boolean,
    val permissionsRequested: List<String>,
    val summary: String
)

data class CommitFileChange(
    val path: String,
    val content: String,
    val isWorkflowFile: Boolean = path.startsWith(".github/workflows/")
)

/**
 * Evaluates license compatibility, dependency conflicts, duplicate functionality, security/secret hygiene,
 * Android SDK / RAM / storage / battery fit, and sensitive-change approval gates.
 */
@Singleton
class RepoSafetyAnalyzer @Inject constructor() {

    private val builtInCapabilities = mapOf(
        "camerax" to "Built-in CameraX + ML Kit OCR/QR (`CameraOcrSheet.kt`)",
        "mlkit" to "Built-in ML Kit text recognition & barcode scanning",
        "room" to "Built-in Room SQLite database (`ZehnDatabase.kt`)",
        "litert" to "Built-in LiteRT-LM on-device inference manager (`OnDeviceLiteRtManager.kt`)",
        "mediapipe" to "Built-in on-device LLM inference interface",
        "accessibilityservice" to "Built-in `ZehnAccessibilityService` GUI loop",
        "callscreening" to "Built-in `ZehnCallScreeningService` & `CallAssistantManager`",
        "gemini-live" to "Built-in `GeminiLiveAudioClient` WebSocket PCM client",
        "workmanager" to "Built-in Jetpack WorkManager scheduler",
        "keystore" to "Built-in AES-256-GCM `KeystoreCryptoManager`"
    )

    private val incompatibleLicenses = setOf("GPL-2.0", "GPL-3.0", "AGPL-3.0", "SSPL-1.0", "WTFPL")
    private val compatibleLicenses = setOf("MIT", "APACHE-2.0", "BSD-3-CLAUSE", "BSD-2-CLAUSE", "ISC", "UNLICENSE", "0BSD", "CC0-1.0")

    fun evaluateLicense(spdxOrRaw: String, readmeText: String = ""): LicenseEvaluation {
        val raw = spdxOrRaw.trim().uppercase(Locale.US)
        val effective = when {
            raw.isNotBlank() && raw != "UNKNOWN" && raw != "NOASSERTION" -> raw
            readmeText.contains("Apache-2.0", ignoreCase = true) || readmeText.contains("Apache License", ignoreCase = true) -> "APACHE-2.0"
            readmeText.contains("MIT License", ignoreCase = true) -> "MIT"
            readmeText.contains("GPL-3", ignoreCase = true) || readmeText.contains("GNU General Public License", ignoreCase = true) -> "GPL-3.0"
            else -> "UNKNOWN"
        }

        return when {
            effective in compatibleLicenses -> LicenseEvaluation(
                effective,
                "COMPATIBLE",
                "Permissive open-source license ($effective) safe for modular Android integration."
            )
            effective in incompatibleLicenses || effective.contains("GPL") -> LicenseEvaluation(
                effective,
                "INCOMPATIBLE",
                "Copyleft license ($effective) is incompatible with proprietary/Play distribution without full source disclosure."
            )
            else -> LicenseEvaluation(
                effective,
                "REVIEW",
                "License '$effective' requires manual verification before code integration."
            )
        }
    }

    fun scanTextSecurity(content: String, path: String = ""): SecurityScanResult {
        val blocking = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val secretPatterns = listOf(
            Regex("""AIza[0-9A-Za-z\-_]{15,}""") to "Potential Google/Gemini API key literal",
            Regex("""sk-[0-9A-Za-z\-_]{10,}""") to "Potential OpenAI secret key",
            Regex("""gsk_[0-9A-Za-z\-_]{10,}""") to "Potential Groq API key",
            Regex("""hf_[0-9A-Za-z\-_]{10,}""") to "Potential Hugging Face token",
            Regex("""(ghp|gho|ghu|ghs|github_pat)_[0-9A-Za-z_]{10,}""") to "Potential GitHub token"
        )
        secretPatterns.forEach { (regex, msg) ->
            if (regex.containsMatchIn(content)) {
                blocking.add("$msg found${if (path.isNotBlank()) " in $path" else ""}")
            }
        }

        val suspiciousPatterns = listOf(
            Regex("""DexClassLoader|PathClassLoader\s*\(.*http""") to "Dynamic external DEX/JAR loading (Play policy violation)",
            Regex("""Runtime\.getRuntime\(\)\.exec\s*\(\s*["'](su|sh|curl|wget)""") to "Suspicious shell/binary execution",
            Regex("""\.apk["']|\.dex["']|\.so["'].*https?://""") to "Unverified binary download pattern"
        )
        suspiciousPatterns.forEach { (regex, msg) ->
            if (regex.containsMatchIn(content)) {
                blocking.add(msg)
            }
        }

        val restrictedPerms = listOf(
            "REQUEST_INSTALL_PACKAGES",
            "MANAGE_EXTERNAL_STORAGE",
            "QUERY_ALL_PACKAGES",
            "READ_SMS",
            "PROCESS_OUTGOING_CALLS"
        )
        restrictedPerms.forEach { perm ->
            if (content.contains(perm)) {
                warnings.add("References Play-restricted permission: $perm")
            }
        }

        return SecurityScanResult(
            clean = blocking.isEmpty(),
            blockingReasons = blocking,
            warnings = warnings
        )
    }

    fun scanCommitDiff(
        changes: List<CommitFileChange>,
        allowWorkflowEdits: Boolean,
        maxFiles: Int = 10,
        maxBytesPerFile: Int = 512 * 1024
    ): SecurityScanResult {
        val blocking = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (changes.isEmpty()) {
            blocking.add("Commit contains no files.")
        }
        if (changes.size > maxFiles) {
            blocking.add("Commit exceeds file cap (${changes.size} > $maxFiles).")
        }

        changes.forEach { file ->
            if (file.path.contains("..") || file.path.startsWith("/")) {
                blocking.add("Invalid repository relative path: ${file.path}")
            }
            if ((file.isWorkflowFile || file.path.startsWith(".github/workflows")) && !allowWorkflowEdits) {
                blocking.add("Workflow edit blocked (${file.path}): 'Allow workflow edits' is OFF.")
            }
            if (file.content.toByteArray().size > maxBytesPerFile) {
                blocking.add("File exceeds 512 KB cap: ${file.path}")
            }
            val fileScan = scanTextSecurity(file.content, file.path)
            blocking.addAll(fileScan.blockingReasons)
            warnings.addAll(fileScan.warnings)
        }

        return SecurityScanResult(
            clean = blocking.isEmpty(),
            blockingReasons = blocking,
            warnings = warnings
        )
    }

    fun detectDuplicateFunctionality(name: String, description: String, dependencies: String): Pair<Boolean, String> {
        val haystack = "$name $description $dependencies".lowercase(Locale.US)
        val matched = builtInCapabilities.entries.firstOrNull { (key, _) ->
            haystack.contains(key)
        }
        return if (matched != null) {
            true to "Overlaps with ${matched.value}"
        } else {
            false to ""
        }
    }

    fun extractMinSdkFromGradle(gradleText: String): Int? {
        if (gradleText.isBlank()) return null
        val match = Regex("""minSdk(?:Version)?\s*[=\s]\s*(\d{2})""").find(gradleText)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    fun extractDependenciesFromGradleOrReadme(gradleText: String, readmeText: String): String {
        val deps = linkedSetOf<String>()
        Regex("""implementation\s*\(\s*["']([^"']+)["']\s*\)""").findAll(gradleText).take(12).forEach {
            deps.add(it.groupValues[1])
        }
        val combined = "$gradleText $readmeText".lowercase(Locale.US)
        listOf("kotlin", "compose", "okhttp", "retrofit", "room", "hilt", "workmanager", "onnx", "llamacpp", "vosk", "sherpa", "mcp", "litert", "silero")
            .forEach { kw -> if (combined.contains(kw)) deps.add(kw) }
        return if (deps.isEmpty()) "Standard Kotlin/HTTP" else deps.joinToString(", ")
    }

    fun evaluateTool(
        source: String,
        identifier: String,
        name: String,
        description: String,
        starsOrDownloads: Int,
        lastUpdated: String,
        rawLicense: String,
        languageOrPipeline: String,
        readmeText: String,
        gradleText: String,
        htmlUrl: String,
        deviceRamMb: Long
    ): DiscoveredToolEntity {
        val licenseEval = evaluateLicense(rawLicense, readmeText)
        val minSdk = extractMinSdkFromGradle(gradleText)
        val depsSummary = extractDependenciesFromGradleOrReadme(gradleText, readmeText)
        val (isDup, dupReason) = detectDuplicateFunctionality(name, description, depsSummary)
        val secScan = scanTextSecurity("$readmeText\n$gradleText", identifier)

        val estRamMb = estimateRamMb(identifier, description, depsSummary)
        val estStorageMb = estimateStorageMb(identifier, description, source)
        val batteryImpact = when {
            estRamMb >= 2500L || identifier.contains("video", true) || identifier.contains("whisper", true) -> "Medium-High (Foreground/On-Device DSP)"
            languageOrPipeline.equals("Kotlin", true) || depsSummary.contains("okhttp", true) -> "Low"
            else -> "Low-Medium"
        }

        // Deliberately multi-factor compatibility scoring — NOT star-only
        var score = 45
        if (licenseEval.status == "COMPATIBLE") score += 20
        else if (licenseEval.status == "INCOMPATIBLE") score -= 35

        if (languageOrPipeline.equals("Kotlin", true) || languageOrPipeline.equals("Java", true) ||
            depsSummary.contains("kotlin", true) || depsSummary.contains("mcp", true) || source == "HUGGINGFACE"
        ) {
            score += 18
        }
        if (minSdk == null || minSdk <= 26) score += 8 else if (minSdk > 31) score -= 12
        if (deviceRamMb > 0L && estRamMb > deviceRamMb / 2) score -= 18
        if (isDup) score -= 10
        if (!secScan.clean) score = 0
        if (starsOrDownloads >= 50) score += 6

        val clampedScore = score.coerceIn(0, 100)
        val securitySummary = when {
            !secScan.clean -> "BLOCKED: ${secScan.blockingReasons.joinToString("; ")}"
            secScan.warnings.isNotEmpty() -> "WARNINGS: ${secScan.warnings.joinToString("; ")}"
            else -> "CLEAR"
        }

        val compatNote = buildString {
            append(licenseEval.note)
            if (minSdk != null) append(" minSdk=$minSdk.")
            if (isDup) append(" Duplicate: $dupReason.")
            if (estRamMb > 0L) append(" Est RAM ~${estRamMb}MB, Storage ~${estStorageMb}MB.")
        }

        return DiscoveredToolEntity(
            source = source,
            identifier = identifier,
            name = name,
            description = description.take(240),
            starsOrDownloads = starsOrDownloads,
            lastUpdated = lastUpdated,
            license = licenseEval.normalizedLicense,
            languageOrPipeline = languageOrPipeline,
            dependenciesSummary = depsSummary.take(220),
            supportedAndroidMinSdk = minSdk,
            estimatedRamMb = estRamMb,
            estimatedStorageMb = estStorageMb,
            batteryImpact = batteryImpact,
            compatibilityScore = clampedScore,
            compatibilityReport = compatNote,
            securityFlags = securitySummary,
            isDuplicate = isDup,
            duplicateReason = dupReason,
            htmlUrl = htmlUrl
        )
    }

    fun assessIntegrationRisk(tool: DiscoveredToolEntity, proposedPaths: List<String>): IntegrationRiskAssessment {
        if (tool.securityFlags.startsWith("BLOCKED") || tool.license in incompatibleLicenses) {
            return IntegrationRiskAssessment(
                riskLevel = "BLOCKED",
                requiresApproval = true,
                permissionsRequested = emptyList(),
                summary = "Blocked by license (${tool.license}) or security check (${tool.securityFlags})."
            )
        }

        val combined = "${tool.name} ${tool.description} ${tool.dependenciesSummary} ${proposedPaths.joinToString(" ")}".lowercase(Locale.US)
        val requestedPerms = mutableListOf<String>()
        if (combined.contains("accessibility")) requestedPerms.add("BIND_ACCESSIBILITY_SERVICE")
        if (combined.contains("call") || combined.contains("telecom") || combined.contains("incall")) requestedPerms.add("READ_PHONE_STATE / ANSWER_PHONE_CALLS")
        if (combined.contains("sms")) requestedPerms.add("SEND_SMS")
        if (combined.contains("microphone") || combined.contains("audio") || combined.contains("speech")) requestedPerms.add("RECORD_AUDIO")
        if (combined.contains("camera") || combined.contains("vision") || combined.contains("ocr")) requestedPerms.add("CAMERA")

        val touchesSensitive = requestedPerms.isNotEmpty() ||
            combined.contains("security") ||
            combined.contains("keystore") ||
            proposedPaths.any { p ->
                p.contains("AndroidManifest.xml") ||
                    p.contains("security/") ||
                    p.contains("Accessibility") ||
                    p.contains("Call") ||
                    p.contains(".github/workflows")
            }

        return if (touchesSensitive) {
            IntegrationRiskAssessment(
                riskLevel = "SENSITIVE",
                requiresApproval = true,
                permissionsRequested = requestedPerms,
                summary = "Modifies sensitive permissions, accessibility, call handling, security, or core config — user approval required before merging."
            )
        } else {
            IntegrationRiskAssessment(
                riskLevel = "LOW",
                requiresApproval = false,
                permissionsRequested = emptyList(),
                summary = "Low-risk modular adapter/feature suitable for automated staging on branch zehn-auto."
            )
        }
    }

    private fun estimateRamMb(id: String, desc: String, deps: String): Long {
        val text = "$id $desc $deps".lowercase(Locale.US)
        return when {
            text.contains("70b") || text.contains("72b") -> 18_000L
            text.contains("7b") || text.contains("8b") || text.contains("9b") -> 6_000L
            text.contains("4b") || text.contains("e4b") -> 4_500L
            text.contains("2b") || text.contains("3b") || text.contains("whisper-large") -> 3_200L
            text.contains("0.6b") || text.contains("1b") || text.contains("sherpa") || text.contains("vosk") || text.contains("tts") -> 650L
            else -> 96L
        }
    }

    private fun estimateStorageMb(id: String, desc: String, source: String): Long {
        val text = "$id $desc".lowercase(Locale.US)
        return when {
            source == "HUGGINGFACE" && (text.contains("4b") || text.contains("7b")) -> 3_800L
            source == "HUGGINGFACE" && (text.contains("1b") || text.contains("2b") || text.contains("0.6b")) -> 1_100L
            source == "HUGGINGFACE" -> 350L
            text.contains("onnx") || text.contains("whisper") || text.contains("litert") -> 180L
            else -> 12L
        }
    }
}
