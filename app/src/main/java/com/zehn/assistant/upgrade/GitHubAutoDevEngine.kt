package com.zehn.assistant.upgrade

import com.zehn.assistant.ai.AiEngineRouter
import com.zehn.assistant.ai.CloudLlmClient
import com.zehn.assistant.ai.LlmMessage
import com.zehn.assistant.data.PreferencesRepository
import com.zehn.assistant.data.db.AutoDevRunEntity
import com.zehn.assistant.data.db.UpgradeCenterDao
import com.zehn.assistant.data.db.UpgradeProposalEntity
import com.zehn.assistant.security.KeystoreCryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class GitHubConnectionStatus(
    val connected: Boolean = false,
    val username: String = "",
    val repoFullName: String = "",
    val defaultBranch: String = "main",
    val canPush: Boolean = false,
    val canPull: Boolean = false,
    val latestBaseSha: String = "",
    val message: String = "Not tested"
)

data class WorkflowBuildStatus(
    val runId: Long = 0L,
    val branch: String = "",
    val status: String = "IDLE", // queued, in_progress, completed, IDLE
    val conclusion: String = "", // success, failure, cancelled, ""
    val htmlUrl: String = "",
    val failedLogTail: String = "",
    val updatedAt: String = ""
)

data class AutoDevExecutionState(
    val running: Boolean = false,
    val activeCycleStage: String = "IDLE", // DISCOVER, ANALYZE, PLAN, IMPLEMENT, TEST, REVIEW, BUILD, NOTIFY
    val currentBranch: String = "",
    val lastBuild: WorkflowBuildStatus = WorkflowBuildStatus(),
    val statusMessage: String = "Idle"
)

/**
 * Real GitHub REST & Git Data API automation engine:
 * - Encrypted Keystore PAT only, never embedded or logged
 * - Pre-commit diff secret & safety scanner + workflow edit guard
 * - Branch creation (zehn-auto/...), atomic Git Data API commits, PR creation, Actions polling,
 *   self-repair log inspection (max 5 attempts), safe approval-gated merge, and rollback.
 */
@Singleton
class GitHubAutoDevEngine @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val crypto: KeystoreCryptoManager,
    private val prefs: PreferencesRepository,
    private val upgradeDao: UpgradeCenterDao,
    private val safetyAnalyzer: RepoSafetyAnalyzer,
    private val aiRouter: AiEngineRouter
) {
    private val _connection = MutableStateFlow(GitHubConnectionStatus())
    val connection: StateFlow<GitHubConnectionStatus> = _connection.asStateFlow()

    private val _devState = MutableStateFlow(AutoDevExecutionState())
    val devState: StateFlow<AutoDevExecutionState> = _devState.asStateFlow()

    @Volatile
    private var stopRequested = false

    fun stopAll() {
        stopRequested = true
        _devState.value = _devState.value.copy(
            running = false,
            activeCycleStage = "STOPPED",
            statusMessage = "Auto-Dev stopped immediately by user."
        )
    }

    suspend fun testConnection(repoOverride: String? = null): GitHubConnectionStatus = withContext(Dispatchers.IO) {
        val settings = prefs.settings.first()
        val repoSlug = (repoOverride ?: settings.githubRepoSlug).trim().trim('/')
        val token = crypto.getSecret(KeystoreCryptoManager.KEY_GITHUB_TOKEN).trim()

        if (token.isBlank()) {
            val missing = GitHubConnectionStatus(connected = false, message = "GitHub token is empty. Add a least-privilege PAT first.")
            _connection.value = missing
            return@withContext missing
        }
        if (!repoSlug.contains('/') || repoSlug.split('/').size != 2) {
            val badRepo = GitHubConnectionStatus(connected = false, message = "Enter repository in 'owner/repo' format.")
            _connection.value = badRepo
            return@withContext badRepo
        }

        val userRes = executeGitHubRequest("GET", "https://api.github.com/user", token)
        if (userRes.isFailure) {
            val err = CloudLlmClient.sanitizeError(userRes.exceptionOrNull()?.message ?: "Auth error")
            val st = GitHubConnectionStatus(connected = false, message = "Token check failed: $err")
            _connection.value = st
            logRun("AUTH_TEST", repoSlug, 401, "GitHub auth failed", err, false)
            return@withContext st
        }

        val userObj = JSONObject(userRes.getOrThrow().second)
        val login = userObj.optString("login", "authenticated")

        val repoRes = executeGitHubRequest("GET", "https://api.github.com/repos/$repoSlug", token)
        if (repoRes.isFailure) {
            val msg = repoRes.exceptionOrNull()?.message ?: "Repository access denied"
            val st = GitHubConnectionStatus(
                connected = false,
                username = login,
                repoFullName = repoSlug,
                message = "Authenticated as @$login, but cannot access '$repoSlug': $msg"
            )
            _connection.value = st
            logRun("AUTH_TEST", repoSlug, 403, "Repo check failed", msg, false)
            return@withContext st
        }

        val repoObj = JSONObject(repoRes.getOrThrow().second)
        val perms = repoObj.optJSONObject("permissions")
        val canPush = perms?.optBoolean("push", false) ?: false
        val canPull = perms?.optBoolean("pull", true) ?: true
        val defBranch = repoObj.optString("default_branch", settings.githubBaseBranch).ifBlank { "main" }

        val refRes = executeGitHubRequest("GET", "https://api.github.com/repos/$repoSlug/git/ref/heads/${settings.githubBaseBranch}", token)
        val baseSha = refRes.getOrNull()?.second?.let {
            runCatching { JSONObject(it).getJSONObject("object").optString("sha", "") }.getOrNull()
        }.orEmpty()

        if (baseSha.isNotBlank() && settings.lastGoodCommitSha.isBlank()) {
            prefs.updateSettings { it.copy(lastGoodCommitSha = baseSha) }
        }

        val permNote = if (!canPush) "READ-ONLY token (add 'Contents: Read & write' + 'Pull requests' + 'Actions' permissions to push/PR)." else "Read + Push verified."
        val ok = GitHubConnectionStatus(
            connected = true,
            username = login,
            repoFullName = repoSlug,
            defaultBranch = defBranch,
            canPush = canPush,
            canPull = canPull,
            latestBaseSha = baseSha,
            message = "Connected as @$login on $repoSlug ($defBranch). $permNote"
        )
        _connection.value = ok
        logRun("AUTH_TEST", repoSlug, 200, "Connected @$login (push=$canPush)", ok.message, true)
        ok
    }

    /**
     * Stages an UpgradeProposal onto its dedicated feature branch (never directly to main/baseBranch),
     * scans every file diff for secrets/safety/workflows, commits atomically via Git Data API,
     * opens a Pull Request, and optionally polls the resulting GitHub Actions run.
     */
    suspend fun executeIntegrationProposal(
        proposalId: Long,
        onBuildFailureNotify: (String) -> Unit = {}
    ): Result<UpgradeProposalEntity> = withContext(Dispatchers.IO) {
        stopRequested = false
        val settings = prefs.settings.first()
        val token = crypto.getSecret(KeystoreCryptoManager.KEY_GITHUB_TOKEN).trim()
        val repo = settings.githubRepoSlug.trim()
        val baseBranch = settings.githubBaseBranch.ifBlank { "main" }

        val proposal = upgradeDao.getProposalById(proposalId)
            ?: return@withContext Result.failure(IllegalArgumentException("Proposal #$proposalId not found."))

        if (proposal.riskLevel == "BLOCKED") {
            return@withContext Result.failure(IllegalStateException("Proposal is blocked by license or security check."))
        }
        if (proposal.requiresApproval && proposal.status != "APPROVED" && proposal.status != "STAGED") {
            return@withContext Result.failure(IllegalStateException("Sensitive proposal requires explicit user approval before integration."))
        }
        if (token.isBlank() || !repo.contains('/')) {
            return@withContext Result.failure(IllegalStateException("Connect GitHub token and 'owner/repo' in Upgrade Center first."))
        }

        val targetBranch = proposal.branchName.ifBlank { "zehn-auto/proposal-${proposal.id}" }
        if (targetBranch == baseBranch || targetBranch == "main" || targetBranch == "master") {
            return@withContext Result.failure(IllegalStateException("Safety rule: never commit directly to protected branch '$baseBranch'."))
        }

        _devState.value = AutoDevExecutionState(
            running = true,
            activeCycleStage = "IMPLEMENT",
            currentBranch = targetBranch,
            statusMessage = "Creating branch $targetBranch from $baseBranch..."
        )

        val branchRes = createOrUpdateBranch(repo, token, targetBranch, baseBranch)
        if (branchRes.isFailure) {
            val err = branchRes.exceptionOrNull()?.message ?: "Branch creation failed"
            val updated = proposal.copy(status = "FAILED", lastErrorReport = err, updatedAt = System.currentTimeMillis())
            upgradeDao.upsertProposal(updated)
            _devState.value = _devState.value.copy(running = false, activeCycleStage = "ERROR", statusMessage = err)
            return@withContext Result.failure(branchRes.exceptionOrNull()!!)
        }

        val baseSha = branchRes.getOrThrow()
        prefs.updateSettings { it.copy(lastGoodCommitSha = baseSha) }

        val changes = parseProposedChanges(proposal)
        val diffScan = safetyAnalyzer.scanCommitDiff(changes, allowWorkflowEdits = settings.allowWorkflowEdits)
        if (!diffScan.clean) {
            val reason = "Pre-commit safety gate blocked commit: ${diffScan.blockingReasons.joinToString("; ")}"
            logRun("COMMIT", targetBranch, 403, "Pre-commit safety blocked", reason, false)
            val updated = proposal.copy(status = "FAILED", lastErrorReport = reason, updatedAt = System.currentTimeMillis())
            upgradeDao.upsertProposal(updated)
            _devState.value = _devState.value.copy(running = false, activeCycleStage = "BLOCKED", statusMessage = reason)
            return@withContext Result.failure(IllegalStateException(reason))
        }

        _devState.value = _devState.value.copy(activeCycleStage = "COMMIT", statusMessage = "Committing ${changes.size} file(s) to $targetBranch...")
        val commitRes = commitChangesAtomic(
            repoSlug = repo,
            token = token,
            branch = targetBranch,
            changes = changes,
            commitMessage = "feat(auto-dev): ${proposal.title}\n\nVerified license & safety scan.\nTool: ${proposal.toolIdentifier}"
        )
        if (commitRes.isFailure) {
            val err = commitRes.exceptionOrNull()?.message ?: "Commit failed"
            val updated = proposal.copy(status = "FAILED", lastErrorReport = err, updatedAt = System.currentTimeMillis())
            upgradeDao.upsertProposal(updated)
            _devState.value = _devState.value.copy(running = false, activeCycleStage = "ERROR", statusMessage = err)
            return@withContext Result.failure(commitRes.exceptionOrNull()!!)
        }

        val commitSha = commitRes.getOrThrow()
        _devState.value = _devState.value.copy(activeCycleStage = "REVIEW", statusMessage = "Opening Pull Request $targetBranch -> $baseBranch...")

        val prRes = openPullRequest(
            repoSlug = repo,
            token = token,
            headBranch = targetBranch,
            baseBranch = baseBranch,
            title = "[Zehn Auto-Dev] ${proposal.title}",
            body = """
                ## Autonomous Integration Proposal
                - **Source**: `${proposal.toolIdentifier}`
                - **Risk Level**: `${proposal.riskLevel}`
                - **Permissions Requested**: `${proposal.permissionsRequested.ifBlank { "None" }}`
                - **Commit SHA**: `$commitSha`

                ### Integration Plan
                ${proposal.integrationPlan}
            """.trimIndent()
        )

        val prNumber = prRes.getOrNull()?.first
        val prUrl = prRes.getOrNull()?.second.orEmpty()

        var staged = proposal.copy(
            status = "STAGED",
            branchName = targetBranch,
            commitSha = commitSha,
            prNumber = prNumber ?: proposal.prNumber,
            prUrl = prUrl.ifBlank { proposal.prUrl },
            lastErrorReport = "",
            updatedAt = System.currentTimeMillis()
        )
        upgradeDao.upsertProposal(staged)

        // Trigger/Poll GitHub Actions Build
        _devState.value = _devState.value.copy(activeCycleStage = "BUILD", statusMessage = "Waiting for GitHub Actions build on $targetBranch...")
        val buildStatus = pollBranchBuild(repo, token, targetBranch, maxPolls = 10)
        _devState.value = _devState.value.copy(lastBuild = buildStatus)

        staged = staged.copy(
            lastBuildConclusion = buildStatus.conclusion.ifBlank { buildStatus.status },
            lastErrorReport = buildStatus.failedLogTail,
            updatedAt = System.currentTimeMillis()
        )
        upgradeDao.upsertProposal(staged)

        if (buildStatus.conclusion == "failure" && settings.autoDevEnabled && !stopRequested) {
            // Bounded self-repair attempt on failure using LLM diff
            staged = attemptBuildSelfRepair(repo, token, staged, buildStatus)
        }

        if (staged.lastBuildConclusion == "success" && settings.autoMergeOnGreen && !staged.requiresApproval && prNumber != null) {
            val mergeRes = mergePullRequest(repo, token, prNumber, staged.title)
            if (mergeRes.isSuccess) {
                staged = staged.copy(status = "MERGED", updatedAt = System.currentTimeMillis())
                upgradeDao.upsertProposal(staged)
            }
        } else if (staged.lastBuildConclusion == "failure" && settings.notifyOnBuildFailure) {
            onBuildFailureNotify("Build failed on $targetBranch: ${staged.lastErrorReport.take(120)}")
        }

        _devState.value = _devState.value.copy(
            running = false,
            activeCycleStage = if (staged.lastBuildConclusion == "success") "NOTIFY" else staged.lastBuildConclusion.uppercase().ifBlank { "STAGED" },
            statusMessage = "Branch $targetBranch -> ${staged.status} (CI: ${staged.lastBuildConclusion.ifBlank { "queued" }})"
        )
        Result.success(staged)
    }

    /**
     * Triggers an immediate manual commit on staging branch or workflow_dispatch build in GitHub Actions.
     */
    suspend fun triggerGitHubActionsBuild(branchOverride: String? = null): Result<String> = withContext(Dispatchers.IO) {
        val settings = prefs.settings.first()
        val token = crypto.getSecret(KeystoreCryptoManager.KEY_GITHUB_TOKEN).trim()
        val repo = settings.githubRepoSlug.trim()
        val ref = branchOverride?.ifBlank { null } ?: settings.githubBaseBranch.ifBlank { "main" }

        if (token.isBlank() || !repo.contains('/')) {
            return@withContext Result.failure(IllegalStateException("Configure GitHub token and repository first."))
        }

        val workflows = listOf("android-build.yml", "build.yml")
        var lastErr = "No dispatchable workflow found"
        for (wf in workflows) {
            val body = JSONObject().put("ref", ref).toString()
            val res = executeGitHubRequest(
                "POST",
                "https://api.github.com/repos/$repo/actions/workflows/$wf/dispatches",
                token,
                body
            )
            if (res.isSuccess) {
                logRun("BUILD_DISPATCH", ref, 204, "Triggered $wf on $ref", "", true)
                _devState.value = _devState.value.copy(activeCycleStage = "BUILD", currentBranch = ref, statusMessage = "Triggered $wf on $ref")
                return@withContext Result.success("Triggered $wf on branch '$ref'.")
            } else {
                lastErr = res.exceptionOrNull()?.message ?: lastErr
            }
        }
        logRun("BUILD_DISPATCH", ref, 404, "Build trigger failed", lastErr, false)
        Result.failure(IllegalStateException("Unable to trigger workflow build: $lastErr"))
    }

    suspend fun pollLatestActionsRun(branch: String? = null): Result<WorkflowBuildStatus> = withContext(Dispatchers.IO) {
        val settings = prefs.settings.first()
        val token = crypto.getSecret(KeystoreCryptoManager.KEY_GITHUB_TOKEN).trim()
        val repo = settings.githubRepoSlug.trim()
        if (token.isBlank() || !repo.contains('/')) {
            return@withContext Result.failure(IllegalStateException("Configure GitHub token & owner/repo first."))
        }
        val status = pollBranchBuild(repo, token, branch ?: settings.githubBaseBranch, maxPolls = 1)
        _devState.value = _devState.value.copy(lastBuild = status)
        Result.success(status)
    }

    /**
     * Rollback a feature branch (e.g. zehn-auto or proposal branch) back to base branch / last known good SHA.
     */
    suspend fun rollbackBranchToStable(branchToReset: String? = null): Result<String> = withContext(Dispatchers.IO) {
        val settings = prefs.settings.first()
        val token = crypto.getSecret(KeystoreCryptoManager.KEY_GITHUB_TOKEN).trim()
        val repo = settings.githubRepoSlug.trim()
        val targetBranch = branchToReset?.ifBlank { null } ?: _devState.value.currentBranch.ifBlank { "zehn-auto" }

        if (token.isBlank() || !repo.contains('/')) {
            return@withContext Result.failure(IllegalStateException("Configure GitHub token & repo first."))
        }

        val stableSha = if (settings.lastGoodCommitSha.isNotBlank()) {
            settings.lastGoodCommitSha
        } else {
            val baseRef = executeGitHubRequest("GET", "https://api.github.com/repos/$repo/git/ref/heads/${settings.githubBaseBranch}", token)
            baseRef.getOrNull()?.second?.let { JSONObject(it).getJSONObject("object").optString("sha", "") }.orEmpty()
        }

        if (stableSha.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Could not resolve stable commit SHA for '${settings.githubBaseBranch}'."))
        }

        val body = JSONObject().put("sha", stableSha).put("force", true).toString()
        val res = executeGitHubRequest("PATCH", "https://api.github.com/repos/$repo/git/refs/heads/$targetBranch", token, body)
        return@withContext res.fold(
            onSuccess = { (code, _) ->
                logRun("ROLLBACK", targetBranch, code, "Rolled back $targetBranch to ${stableSha.take(7)}", "", true)
                _devState.value = _devState.value.copy(
                    running = false,
                    activeCycleStage = "ROLLED_BACK",
                    statusMessage = "Restored '$targetBranch' to stable SHA ${stableSha.take(7)}."
                )
                Result.success("Restored '$targetBranch' to stable commit ${stableSha.take(7)}.")
            },
            onFailure = { err ->
                val msg = CloudLlmClient.sanitizeError(err.message ?: "Rollback failed")
                logRun("ROLLBACK", targetBranch, 500, "Rollback failed", msg, false)
                Result.failure(IllegalStateException(msg))
            }
        )
    }

    internal suspend fun createOrUpdateBranch(
        repoSlug: String,
        token: String,
        newBranch: String,
        baseBranch: String
    ): Result<String> {
        val baseRef = executeGitHubRequest("GET", "https://api.github.com/repos/$repoSlug/git/ref/heads/$baseBranch", token)
        if (baseRef.isFailure) return Result.failure(baseRef.exceptionOrNull()!!)

        val baseSha = JSONObject(baseRef.getOrThrow().second).getJSONObject("object").getString("sha")
        val existingBranch = executeGitHubRequest("GET", "https://api.github.com/repos/$repoSlug/git/ref/heads/$newBranch", token)
        return if (existingBranch.isSuccess) {
            val patchBody = JSONObject().put("sha", baseSha).put("force", false).toString()
            executeGitHubRequest("PATCH", "https://api.github.com/repos/$repoSlug/git/refs/heads/$newBranch", token, patchBody)
            logRun("BRANCH", newBranch, 200, "Updated existing branch from $baseBranch", baseSha.take(7), true)
            Result.success(baseSha)
        } else {
            val createBody = JSONObject()
                .put("ref", "refs/heads/$newBranch")
                .put("sha", baseSha)
                .toString()
            val created = executeGitHubRequest("POST", "https://api.github.com/repos/$repoSlug/git/refs", token, createBody)
            created.map {
                logRun("BRANCH", newBranch, 201, "Created branch from $baseBranch", baseSha.take(7), true)
                baseSha
            }
        }
    }

    internal suspend fun commitChangesAtomic(
        repoSlug: String,
        token: String,
        branch: String,
        changes: List<CommitFileChange>,
        commitMessage: String
    ): Result<String> {
        val refRes = executeGitHubRequest("GET", "https://api.github.com/repos/$repoSlug/git/ref/heads/$branch", token)
        if (refRes.isFailure) return Result.failure(refRes.exceptionOrNull()!!)
        val headCommitSha = JSONObject(refRes.getOrThrow().second).getJSONObject("object").getString("sha")

        val commitObjRes = executeGitHubRequest("GET", "https://api.github.com/repos/$repoSlug/git/commits/$headCommitSha", token)
        if (commitObjRes.isFailure) return Result.failure(commitObjRes.exceptionOrNull()!!)
        val baseTreeSha = JSONObject(commitObjRes.getOrThrow().second).getJSONObject("tree").getString("sha")

        val treeItems = JSONArray()
        for (file in changes) {
            val blobReq = JSONObject()
                .put("content", file.content)
                .put("encoding", "utf-8")
                .toString()
            val blobRes = executeGitHubRequest("POST", "https://api.github.com/repos/$repoSlug/git/blobs", token, blobReq)
            if (blobRes.isFailure) return Result.failure(blobRes.exceptionOrNull()!!)
            val blobSha = JSONObject(blobRes.getOrThrow().second).getString("sha")
            treeItems.put(
                JSONObject()
                    .put("path", file.path)
                    .put("mode", "100644")
                    .put("type", "blob")
                    .put("sha", blobSha)
            )
        }

        val treeReq = JSONObject()
            .put("base_tree", baseTreeSha)
            .put("tree", treeItems)
            .toString()
        val treeRes = executeGitHubRequest("POST", "https://api.github.com/repos/$repoSlug/git/trees", token, treeReq)
        if (treeRes.isFailure) return Result.failure(treeRes.exceptionOrNull()!!)
        val newTreeSha = JSONObject(treeRes.getOrThrow().second).getString("sha")

        val newCommitReq = JSONObject()
            .put("message", commitMessage)
            .put("tree", newTreeSha)
            .put("parents", JSONArray().put(headCommitSha))
            .toString()
        val newCommitRes = executeGitHubRequest("POST", "https://api.github.com/repos/$repoSlug/git/commits", token, newCommitReq)
        if (newCommitRes.isFailure) return Result.failure(newCommitRes.exceptionOrNull()!!)
        val newCommitSha = JSONObject(newCommitRes.getOrThrow().second).getString("sha")

        val updateRefReq = JSONObject().put("sha", newCommitSha).toString()
        val updateRefRes = executeGitHubRequest("PATCH", "https://api.github.com/repos/$repoSlug/git/refs/heads/$branch", token, updateRefReq)
        if (updateRefRes.isFailure) return Result.failure(updateRefRes.exceptionOrNull()!!)

        logRun("COMMIT", branch, 201, "Committed ${changes.size} files (${newCommitSha.take(7)})", commitMessage.lines().firstOrNull().orEmpty(), true)
        return Result.success(newCommitSha)
    }

    internal suspend fun openPullRequest(
        repoSlug: String,
        token: String,
        headBranch: String,
        baseBranch: String,
        title: String,
        body: String
    ): Result<Pair<Int, String>> {
        val req = JSONObject()
            .put("title", title)
            .put("head", headBranch)
            .put("base", baseBranch)
            .put("body", body)
            .toString()
        val res = executeGitHubRequest("POST", "https://api.github.com/repos/$repoSlug/pulls", token, req)
        return res.mapCatching { (code, payload) ->
            val obj = JSONObject(payload)
            val num = obj.getInt("number")
            val html = obj.optString("html_url", "")
            logRun("PR", headBranch, code, "Opened PR #$num -> $baseBranch", html, true)
            num to html
        }
    }

    suspend fun mergePullRequest(
        repoSlug: String,
        token: String,
        prNumber: Int,
        commitTitle: String
    ): Result<String> {
        val req = JSONObject()
            .put("commit_title", commitTitle)
            .put("merge_method", "squash")
            .toString()
        val res = executeGitHubRequest("PUT", "https://api.github.com/repos/$repoSlug/pulls/$prNumber/merge", token, req)
        return res.mapCatching { (code, payload) ->
            val obj = JSONObject(payload)
            val sha = obj.optString("sha", "")
            logRun("MERGE", "PR #$prNumber", code, "Merged PR #$prNumber (${sha.take(7)})", "", true)
            if (sha.isNotBlank()) prefs.updateSettings { it.copy(lastGoodCommitSha = sha) }
            sha
        }
    }

    private suspend fun pollBranchBuild(
        repoSlug: String,
        token: String,
        branch: String,
        maxPolls: Int
    ): WorkflowBuildStatus {
        var lastStatus = WorkflowBuildStatus(branch = branch)
        repeat(maxPolls) { idx ->
            if (stopRequested) return lastStatus
            val url = "https://api.github.com/repos/$repoSlug/actions/runs?branch=$branch&per_page=3"
            val res = executeGitHubRequest("GET", url, token)
            if (res.isSuccess) {
                val obj = JSONObject(res.getOrThrow().second)
                val runs = obj.optJSONArray("workflow_runs")
                if (runs != null && runs.length() > 0) {
                    val top = runs.getJSONObject(0)
                    val runId = top.optLong("id", 0L)
                    val st = top.optString("status", "queued")
                    val conc = top.optString("conclusion", "")
                    val htmlUrl = top.optString("html_url", "")
                    val upd = top.optString("updated_at", "")
                    val failTail = if (conc == "failure") fetchFailedJobLogTail(repoSlug, token, runId) else ""
                    lastStatus = WorkflowBuildStatus(
                        runId = runId,
                        branch = branch,
                        status = st,
                        conclusion = conc,
                        htmlUrl = htmlUrl,
                        failedLogTail = failTail,
                        updatedAt = upd
                    )
                    logRun("BUILD_POLL", branch, 200, "Run #$runId: $st / ${conc.ifBlank { "pending" }}", htmlUrl, conc != "failure")
                    if (st == "completed") return lastStatus
                }
            }
            if (idx < maxPolls - 1) delay(6_000L)
        }
        return lastStatus
    }

    private suspend fun fetchFailedJobLogTail(repoSlug: String, token: String, runId: Long): String {
        if (runId <= 0L) return ""
        val jobsRes = executeGitHubRequest("GET", "https://api.github.com/repos/$repoSlug/actions/runs/$runId/jobs", token)
        val jobs = jobsRes.getOrNull()?.second?.let { runCatching { JSONObject(it).optJSONArray("jobs") }.getOrNull() } ?: return ""
        for (i in 0 until jobs.length()) {
            val job = jobs.optJSONObject(i) ?: continue
            if (job.optString("conclusion") == "failure") {
                val jobId = job.optLong("id", 0L)
                val logRes = executeGitHubRequest("GET", "https://api.github.com/repos/$repoSlug/actions/jobs/$jobId/logs", token)
                val logText = logRes.getOrNull()?.second ?: continue
                return logText.lines().takeLast(200).joinToString("\n").take(6000)
            }
        }
        return ""
    }

    private suspend fun attemptBuildSelfRepair(
        repoSlug: String,
        token: String,
        proposal: UpgradeProposalEntity,
        failedBuild: WorkflowBuildStatus
    ): UpgradeProposalEntity {
        var current = proposal
        var attempt = 1
        while (attempt <= 5 && !stopRequested && current.lastBuildConclusion == "failure") {
            _devState.value = _devState.value.copy(
                activeCycleStage = "SELF_REPAIR ($attempt/5)",
                statusMessage = "Analyzing failed CI log with AI brain (attempt $attempt/5)..."
            )
            val prompt = """
                A GitHub Actions Android Gradle build failed on branch '${current.branchName}'.
                Propose a minimal safe Kotlin fix comment or patch note as JSON:
                {"path":"app/src/main/java/com/zehn/assistant/upgrade/adapters/BuildFixNote_${current.id}.md","content":"..."}
                Build log tail:
                ${failedBuild.failedLogTail.take(2500)}
            """.trimIndent()
            val reply = aiRouter.routeAndStream(emptyList(), prompt, null).toList()
                .joinToString("") { it.delta }

            if (reply.isBlank()) break

            val fixNote = CommitFileChange(
                path = "docs/auto_dev_repair_${current.id}_attempt_$attempt.md",
                content = "# Auto-Dev Repair Analysis Attempt $attempt\n\n```\n${CloudLlmClient.sanitizeError(failedBuild.failedLogTail.take(1500))}\n```\n\n## AI Diagnosis\n${CloudLlmClient.sanitizeError(reply.take(2500))}\n"
            )
            val scan = safetyAnalyzer.scanCommitDiff(listOf(fixNote), allowWorkflowEdits = false)
            if (!scan.clean) break

            val commitRes = commitChangesAtomic(repoSlug, token, current.branchName, listOf(fixNote), "chore(auto-dev): diagnostics pass #$attempt")
            if (commitRes.isFailure) break

            val nextBuild = pollBranchBuild(repoSlug, token, current.branchName, maxPolls = 4)
            current = current.copy(
                commitSha = commitRes.getOrThrow(),
                lastBuildConclusion = nextBuild.conclusion.ifBlank { nextBuild.status },
                lastErrorReport = nextBuild.failedLogTail,
                updatedAt = System.currentTimeMillis()
            )
            upgradeDao.upsertProposal(current)
            if (current.lastBuildConclusion == "success") break
            attempt++
        }
        return current
    }

    private fun parseProposedChanges(proposal: UpgradeProposalEntity): List<CommitFileChange> {
        val out = mutableListOf<CommitFileChange>()
        runCatching {
            val arr = JSONArray(proposal.proposedFilesJson)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val p = obj.optString("path", "").trim()
                val c = obj.optString("content", "")
                if (p.isNotBlank() && c.isNotBlank()) {
                    out.add(CommitFileChange(p, c))
                }
            }
        }
        if (out.isEmpty()) {
            out.add(
                CommitFileChange(
                    path = "docs/proposals/proposal_${proposal.id}.md",
                    content = "# ${proposal.title}\n\n${proposal.integrationPlan}\n"
                )
            )
        }
        return out
    }

    private suspend fun logRun(
        action: String,
        branch: String,
        code: Int,
        summary: String,
        detail: String,
        success: Boolean
    ) {
        upgradeDao.insertDevRun(
            AutoDevRunEntity(
                actionType = action,
                branchOrRef = branch,
                httpStatus = code,
                summary = summary,
                detail = CloudLlmClient.sanitizeError(detail).take(1500),
                success = success
            )
        )
    }

    private suspend fun executeGitHubRequest(
        method: String,
        url: String,
        token: String,
        jsonBody: String? = null
    ): Result<Pair<Int, String>> {
        var backoffMs = 800L
        repeat(3) { attempt ->
            val media = "application/json; charset=utf-8".toMediaType()
            val req = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("X-GitHub-Api-Version", "2022-11-28")
                .addHeader("User-Agent", "ZehnAI-AutoDev/4.0")
                .addHeader("Authorization", "Bearer $token")
                .method(method, jsonBody?.toRequestBody(media))
                .build()

            val exec = runCatching {
                okHttpClient.newCall(req).execute().use { resp ->
                    resp.code to resp.body?.string().orEmpty()
                }
            }
            exec.onFailure { err ->
                if (attempt == 2) return Result.failure(err)
            }.onSuccess { (code, body) ->
                if (code in 200..299) return Result.success(code to body)
                if ((code == 403 || code == 429) && attempt < 2 && body.contains("rate limit", ignoreCase = true)) {
                    delay(backoffMs)
                    backoffMs *= 2
                } else {
                    val snippet = CloudLlmClient.sanitizeError(body.take(240))
                    return Result.failure(IllegalStateException("HTTP $code: $snippet"))
                }
            }
        }
        return Result.failure(IllegalStateException("GitHub API failed after retries"))
    }
}
