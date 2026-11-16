package com.zehn.assistant.upgrade

import android.util.Base64
import com.zehn.assistant.ai.OnDeviceLiteRtManager
import com.zehn.assistant.data.PreferencesRepository
import com.zehn.assistant.data.db.AutoDevRunEntity
import com.zehn.assistant.data.db.DiscoveredToolEntity
import com.zehn.assistant.data.db.UpgradeCenterDao
import com.zehn.assistant.data.db.UpgradeProposalEntity
import com.zehn.assistant.security.KeystoreCryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

data class ResearchCycleSummary(
    val running: Boolean = false,
    val currentStage: String = "IDLE",
    val newlyDiscoveredCount: Int = 0,
    val skippedDuplicatesCount: Int = 0,
    val proposalsCreatedCount: Int = 0,
    val lastError: String = ""
)

/**
 * Autonomous GitHub & Hugging Face research agent covering all 14 GitHub capability domains
 * and 6 Hugging Face model tracks, deduplicating via Room, inspecting READMEs & Gradle specs,
 * checking licenses & security, and creating concrete integration plans.
 */
@Singleton
class AutonomousResearchAgent @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val crypto: KeystoreCryptoManager,
    private val prefs: PreferencesRepository,
    private val upgradeDao: UpgradeCenterDao,
    private val liteRtManager: OnDeviceLiteRtManager,
    private val safetyAnalyzer: RepoSafetyAnalyzer
) {
    private val _state = MutableStateFlow(ResearchCycleSummary())
    val state: StateFlow<ResearchCycleSummary> = _state.asStateFlow()

    @Volatile
    private var stopRequested = false

    val githubTopics = listOf(
        "android ai assistant kotlin",
        "android voice assistant stt tts",
        "android accessibilityservice automation",
        "computer use screen automation agent",
        "android ai phone call assistant",
        "android telecom incallservice",
        "model context protocol mcp kotlin android",
        "ai agent framework kotlin",
        "mobile rag memory vector sqlite",
        "android camerax mlkit ocr vision",
        "offline speech recognition tts android",
        "on-device llm android litert gemma",
        "android ui automation library kotlin",
        "android security keystore privacy harden"
    )

    val huggingFaceQueries = listOf(
        "urdu speech recognition",
        "multilingual tts",
        "gemma 1b 2b it",
        "vision ocr mobile",
        "litert tflite on-device",
        "qwen3 0.6b"
    )

    fun stopResearch() {
        stopRequested = true
        _state.value = _state.value.copy(running = false, currentStage = "STOPPED")
    }

    suspend fun runFullResearchCycle(
        includeGitHub: Boolean = true,
        includeHuggingFace: Boolean = true,
        forceRescanExisting: Boolean = false,
        focusModeOverride: String? = null,
        customQuery: String? = null
    ): Result<ResearchCycleSummary> = withContext(Dispatchers.IO) {
        stopRequested = false
        val settings = prefs.settings.first()
        val focusMode = focusModeOverride ?: settings.researchFocusMode
        val deviceRamMb = liteRtManager.state.value.totalRamMb

        var newCount = 0
        var skippedCount = 0
        var proposalCount = 0
        val errors = mutableListOf<String>()

        _state.value = ResearchCycleSummary(running = true, currentStage = "DISCOVER")

        try {
            if (includeGitHub && !stopRequested) {
                val queries = when {
                    !customQuery.isNullOrBlank() -> listOf(customQuery.trim())
                    focusMode == "SECURITY_ONLY" -> listOf(
                        "android security keystore privacy harden",
                        "android accessibilityservice security"
                    )
                    else -> githubTopics.take(5)
                }

                for (q in queries) {
                    if (stopRequested) break
                    val reposRes = fetchGitHubRepositories(q, perPage = 4)
                    reposRes.onFailure { err ->
                        errors.add("GitHub ($q): ${err.message}")
                    }.onSuccess { rawRepos ->
                        for (repo in rawRepos) {
                            if (stopRequested) break
                            val existing = upgradeDao.findDiscoveredTool(repo.fullName)
                            if (existing != null && !forceRescanExisting) {
                                skippedCount++
                                continue
                            }

                            _state.value = _state.value.copy(currentStage = "ANALYZE: ${repo.fullName}")
                            val readme = fetchGitHubReadme(repo.fullName)
                            val gradle = fetchGitHubBuildGradle(repo.fullName)

                            val evaluated = safetyAnalyzer.evaluateTool(
                                source = "GITHUB",
                                identifier = repo.fullName,
                                name = repo.fullName.substringAfter('/'),
                                description = repo.description,
                                starsOrDownloads = repo.stars,
                                lastUpdated = repo.updatedAt,
                                rawLicense = repo.license,
                                languageOrPipeline = repo.language,
                                readmeText = readme,
                                gradleText = gradle,
                                htmlUrl = repo.htmlUrl,
                                deviceRamMb = deviceRamMb
                            )
                            upgradeDao.upsertDiscoveredTool(evaluated)
                            newCount++

                            if (maybeCreateProposal(evaluated)) {
                                proposalCount++
                            }
                        }
                    }
                }
            }

            if (includeHuggingFace && !stopRequested) {
                val hfQueries = if (!customQuery.isNullOrBlank()) listOf(customQuery.trim()) else huggingFaceQueries.take(4)
                for (q in hfQueries) {
                    if (stopRequested) break
                    _state.value = _state.value.copy(currentStage = "DISCOVER HF: $q")
                    val hfRes = fetchHuggingFaceModels(q, limit = 4)
                    hfRes.onFailure { err ->
                        errors.add("HF ($q): ${err.message}")
                    }.onSuccess { models ->
                        for (m in models) {
                            if (stopRequested) break
                            val existing = upgradeDao.findDiscoveredTool(m.modelId)
                            if (existing != null && !forceRescanExisting) {
                                skippedCount++
                                continue
                            }
                            val evaluated = safetyAnalyzer.evaluateTool(
                                source = "HUGGINGFACE",
                                identifier = m.modelId,
                                name = m.modelId.substringAfter('/'),
                                description = "Pipeline: ${m.pipelineTag}, Languages: ${m.languages}",
                                starsOrDownloads = m.downloads,
                                lastUpdated = m.lastModified,
                                rawLicense = m.license,
                                languageOrPipeline = m.pipelineTag,
                                readmeText = "Model ${m.modelId} ${m.pipelineTag} ${m.languages}",
                                gradleText = "",
                                htmlUrl = m.htmlUrl,
                                deviceRamMb = deviceRamMb
                            )
                            upgradeDao.upsertDiscoveredTool(evaluated)
                            newCount++

                            if (maybeCreateProposal(evaluated)) {
                                proposalCount++
                            }
                        }
                    }
                }
            }

            val now = System.currentTimeMillis()
            val intervalMs = when (settings.researchScheduleMode) {
                "DAILY" -> 24L * 60 * 60 * 1000
                "WEEKLY" -> 7L * 24L * 60 * 60 * 1000
                else -> 0L
            }
            prefs.updateSettings {
                it.copy(
                    lastResearchTimestamp = now,
                    nextScheduledResearchTimestamp = if (intervalMs > 0L && !it.researchPaused) now + intervalMs else 0L
                )
            }

            upgradeDao.insertDevRun(
                AutoDevRunEntity(
                    actionType = "RESEARCH",
                    branchOrRef = settings.githubBaseBranch,
                    httpStatus = if (errors.isEmpty()) 200 else 207,
                    summary = "Discovered $newCount tools, skipped $skippedCount cached, created $proposalCount plans",
                    detail = errors.joinToString("\n").ifBlank { "Completed cleanly." },
                    success = errors.isEmpty() || newCount > 0
                )
            )

            val done = ResearchCycleSummary(
                running = false,
                currentStage = if (stopRequested) "STOPPED" else "COMPLETE",
                newlyDiscoveredCount = newCount,
                skippedDuplicatesCount = skippedCount,
                proposalsCreatedCount = proposalCount,
                lastError = errors.joinToString("; ")
            )
            _state.value = done
            Result.success(done)
        } catch (t: Throwable) {
            val errMsg = t.message ?: "Research failed"
            _state.value = ResearchCycleSummary(
                running = false,
                currentStage = "ERROR",
                newlyDiscoveredCount = newCount,
                skippedDuplicatesCount = skippedCount,
                proposalsCreatedCount = proposalCount,
                lastError = errMsg
            )
            upgradeDao.insertDevRun(
                AutoDevRunEntity(
                    actionType = "RESEARCH",
                    branchOrRef = settings.githubBaseBranch,
                    httpStatus = 0,
                    summary = "Research cycle error",
                    detail = errMsg,
                    success = false
                )
            )
            Result.failure(t)
        }
    }

    private suspend fun maybeCreateProposal(tool: DiscoveredToolEntity): Boolean {
        if (tool.compatibilityScore < 55 || tool.securityFlags.startsWith("BLOCKED")) return false
        val existingProp = upgradeDao.findProposalByTool(tool.identifier)
        if (existingProp != null) return false

        val sanitizedSlug = tool.name.replace(Regex("[^A-Za-z0-9]"), "").take(24).ifBlank { "ToolAdapter" }
        val adapterFilePath = "app/src/main/java/com/zehn/assistant/upgrade/adapters/${sanitizedSlug}AdapterMetadata.kt"
        val risk = safetyAnalyzer.assessIntegrationRisk(tool, listOf(adapterFilePath))
        val status = if (risk.riskLevel == "BLOCKED") "FAILED" else "PENDING"

        val plan = buildString {
            appendLine("Integration Plan for ${tool.identifier} (${tool.source})")
            appendLine("1. Verify license (${tool.license}) & dependency compatibility (${tool.dependenciesSummary}).")
            appendLine("2. Create modular adapter interface under com.zehn.assistant.upgrade.adapters without altering existing core APIs.")
            appendLine("3. Enforce safety boundary: no dynamic binary/DEX execution; preserve Room schema & Keystore encryption.")
            appendLine("4. Target branch: zehn-auto/integrate-${tool.name.lowercase().replace(Regex("[^a-z0-9]"), "-")}")
            appendLine("5. Run unit/Robolectric tests, Lint, and GitHub Actions APK build before notifying user.")
        }

        val generatedAdapterCode = """
            package com.zehn.assistant.upgrade.adapters

            /**
             * Auto-generated modular capability descriptor for ${tool.identifier}
             * License: ${tool.license} | Source: ${tool.htmlUrl}
             * Risk Assessment: ${risk.riskLevel} | Compatibility Score: ${tool.compatibilityScore}/100
             */
            object ${sanitizedSlug}AdapterMetadata {
                const val SOURCE_ID: String = "${tool.identifier}"
                const val SOURCE_TYPE: String = "${tool.source}"
                const val LICENSE: String = "${tool.license}"
                const val COMPATIBILITY_SCORE: Int = ${tool.compatibilityScore}
                const val REQUIRES_APPROVAL: Boolean = ${risk.requiresApproval}
            }
        """.trimIndent()

        val filesJson = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("path", adapterFilePath)
                    put("content", generatedAdapterCode)
                }
            )
        }.toString()

        upgradeDao.upsertProposal(
            UpgradeProposalEntity(
                toolIdentifier = tool.identifier,
                title = "Integrate ${tool.name} adapter",
                summary = "${tool.description} (Score ${tool.compatibilityScore}/100, RAM ~${tool.estimatedRamMb}MB)",
                integrationPlan = plan,
                proposedFilesJson = filesJson,
                permissionsRequested = risk.permissionsRequested.joinToString(", "),
                riskLevel = risk.riskLevel,
                requiresApproval = risk.requiresApproval,
                status = status,
                branchName = "zehn-auto/feat-${tool.name.lowercase().replace(Regex("[^a-z0-9]"), "-").take(24)}"
            )
        )
        return true
    }

    internal data class RawGitHubRepo(
        val fullName: String,
        val description: String,
        val stars: Int,
        val license: String,
        val language: String,
        val updatedAt: String,
        val htmlUrl: String
    )

    internal data class RawHfModel(
        val modelId: String,
        val pipelineTag: String,
        val downloads: Int,
        val license: String,
        val languages: String,
        val lastModified: String,
        val htmlUrl: String
    )

    private suspend fun fetchGitHubRepositories(query: String, perPage: Int): Result<List<RawGitHubRepo>> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.github.com/search/repositories?q=$encoded&sort=updated&order=desc&per_page=$perPage"
        return executeGitHubGetWithBackoff(url).mapCatching { body ->
            parseGitHubSearchPayload(body)
        }
    }

    internal fun parseGitHubSearchPayload(json: String): List<RawGitHubRepo> {
        val root = JSONObject(json)
        val items = root.optJSONArray("items") ?: return emptyList()
        val list = mutableListOf<RawGitHubRepo>()
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            val fullName = obj.optString("full_name", "").trim()
            if (fullName.isBlank()) continue
            list.add(
                RawGitHubRepo(
                    fullName = fullName,
                    description = obj.optString("description", "No description").ifBlank { "No description" },
                    stars = obj.optInt("stargazers_count", 0),
                    license = obj.optJSONObject("license")?.optString("spdx_id", "Unknown").orEmpty().ifBlank { "Unknown" },
                    language = obj.optString("language", "Mixed").ifBlank { "Mixed" },
                    updatedAt = obj.optString("pushed_at", "").take(10),
                    htmlUrl = obj.optString("html_url", "https://github.com/$fullName")
                )
            )
        }
        return list
    }

    private suspend fun fetchGitHubReadme(fullName: String): String {
        val url = "https://api.github.com/repos/$fullName/readme"
        val res = executeGitHubGetWithBackoff(url).getOrNull() ?: return ""
        return runCatching {
            val obj = JSONObject(res)
            val b64 = obj.optString("content", "").replace("\n", "")
            if (b64.isBlank()) "" else String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8).take(4000)
        }.getOrDefault("")
    }

    private suspend fun fetchGitHubBuildGradle(fullName: String): String {
        val paths = listOf("app/build.gradle.kts", "app/build.gradle", "build.gradle.kts", "package.json", "requirements.txt")
        for (path in paths) {
            val res = executeGitHubGetWithBackoff("https://api.github.com/repos/$fullName/contents/$path").getOrNull() ?: continue
            val decoded = runCatching {
                val obj = JSONObject(res)
                val b64 = obj.optString("content", "").replace("\n", "")
                if (b64.isBlank()) "" else String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8).take(4000)
            }.getOrDefault("")
            if (decoded.isNotBlank()) return decoded
        }
        return ""
    }

    private suspend fun fetchHuggingFaceModels(query: String, limit: Int): Result<List<RawHfModel>> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://huggingface.co/api/models?search=$encoded&sort=downloads&direction=-1&limit=$limit"
        return runCatching {
            val builder = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .get()
            val hfToken = crypto.getSecret(KeystoreCryptoManager.KEY_HF_TOKEN).trim()
            if (hfToken.isNotBlank()) builder.addHeader("Authorization", "Bearer $hfToken")

            okHttpClient.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                parseHuggingFacePayload(body)
            }
        }
    }

    internal fun parseHuggingFacePayload(json: String): List<RawHfModel> {
        val arr = JSONArray(json)
        val out = mutableListOf<RawHfModel>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id", obj.optString("modelId", "")).trim()
            if (id.isBlank()) continue
            val tags = obj.optJSONArray("tags")
            var license = "Check card"
            val langs = mutableListOf<String>()
            if (tags != null) {
                for (t in 0 until tags.length()) {
                    val tag = tags.optString(t)
                    if (tag.startsWith("license:")) license = tag.removePrefix("license:")
                    else if (tag in setOf("en", "ur", "hi", "multilingual")) langs.add(tag.uppercase())
                }
            }
            out.add(
                RawHfModel(
                    modelId = id,
                    pipelineTag = obj.optString("pipeline_tag", "text-generation").ifBlank { "text-generation" },
                    downloads = obj.optInt("downloads", 0),
                    license = license,
                    languages = langs.ifEmpty { listOf("EN/Multi") }.joinToString(","),
                    lastModified = obj.optString("lastModified", "").take(10),
                    htmlUrl = "https://huggingface.co/$id"
                )
            )
        }
        return out
    }

    private suspend fun executeGitHubGetWithBackoff(url: String): Result<String> {
        val token = crypto.getSecret(KeystoreCryptoManager.KEY_GITHUB_TOKEN).trim()
        var delayMs = 800L
        repeat(3) { attempt ->
            val req = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("X-GitHub-Api-Version", "2022-11-28")
                .addHeader("User-Agent", "ZehnAI-AutonomousResearch/4.0")
                .apply {
                    if (token.isNotBlank()) addHeader("Authorization", "Bearer $token")
                }
                .get()
                .build()

            val callRes = runCatching {
                okHttpClient.newCall(req).execute().use { resp ->
                    val code = resp.code
                    val body = resp.body?.string().orEmpty()
                    Triple(code, resp.header("X-RateLimit-Remaining"), body)
                }
            }

            callRes.onFailure { err ->
                if (attempt == 2) return Result.failure(err)
            }.onSuccess { (code, _, body) ->
                if (code in 200..299) return Result.success(body)
                if ((code == 403 || code == 429) && attempt < 2) {
                    delay(delayMs)
                    delayMs *= 2
                } else {
                    return Result.failure(IllegalStateException("GitHub API HTTP $code"))
                }
            }
        }
        return Result.failure(IllegalStateException("GitHub API retries exhausted"))
    }
}
