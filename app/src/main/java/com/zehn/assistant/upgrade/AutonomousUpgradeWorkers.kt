package com.zehn.assistant.upgrade

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zehn.assistant.data.PreferencesRepository
import com.zehn.assistant.data.db.UpgradeCenterDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
interface UpgradeWorkerEntryPoint {
    fun preferencesRepository(): PreferencesRepository
    fun researchAgent(): AutonomousResearchAgent
    fun autoDevEngine(): GitHubAutoDevEngine
    fun releaseUpdateManager(): ReleaseUpdateManager
    fun upgradeCenterDao(): UpgradeCenterDao
}

class ResearchScheduleWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, UpgradeWorkerEntryPoint::class.java)
        val prefs = deps.preferencesRepository()
        val settings = prefs.settings.first()
        if (settings.researchPaused) return Result.success()

        // Step 1: DISCOVER -> ANALYZE -> PLAN
        deps.researchAgent().runFullResearchCycle(
            includeGitHub = true,
            includeHuggingFace = true
        )

        // Step 2: Optional low-risk staging if Auto-Dev is explicitly enabled
        if (settings.autoDevEnabled) {
            val pendingLowRisk = deps.upgradeCenterDao().observeProposals().first()
                .firstOrNull { !it.requiresApproval && it.riskLevel == "LOW" && it.status == "PENDING" }
            if (pendingLowRisk != null) {
                deps.autoDevEngine().executeIntegrationProposal(
                    proposalId = pendingLowRisk.id,
                    onBuildFailureNotify = { err ->
                        if (settings.notifyOnBuildFailure) {
                            deps.releaseUpdateManager().showBuildFailureNotification(err)
                        }
                    }
                )
            }
        }

        // Step 3: Check for verified published GitHub Releases
        if (settings.githubRepoSlug.contains('/')) {
            deps.releaseUpdateManager().checkForVerifiedReleaseUpdate(notifyIfNewer = true)
        }
        return Result.success()
    }
}

class ReleaseCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, UpgradeWorkerEntryPoint::class.java)
        val settings = deps.preferencesRepository().settings.first()
        if (!settings.githubRepoSlug.contains('/')) return Result.success()
        deps.releaseUpdateManager().checkForVerifiedReleaseUpdate(notifyIfNewer = true)
        return Result.success()
    }
}

@Singleton
class UpgradeScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PreferencesRepository
) {
    suspend fun applySchedule(mode: String, focusMode: String, paused: Boolean) {
        val intervalHours = when (mode) {
            "DAILY" -> 24L
            "WEEKLY" -> 24L * 7L
            else -> 0L
        }
        val nextTs = if (intervalHours > 0L && !paused) {
            System.currentTimeMillis() + TimeUnit.HOURS.toMillis(intervalHours)
        } else {
            0L
        }

        prefs.updateSettings {
            it.copy(
                researchScheduleMode = mode,
                researchFocusMode = focusMode,
                researchPaused = paused,
                nextScheduledResearchTimestamp = nextTs
            )
        }

        val wm = WorkManager.getInstance(context)
        if (paused || intervalHours == 0L) {
            wm.cancelUniqueWork(WORK_PERIODIC_RESEARCH)
        } else {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val req = PeriodicWorkRequestBuilder<ResearchScheduleWorker>(intervalHours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(WORK_PERIODIC_RESEARCH, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        // Periodic verified GitHub Release watcher (every 12 hours when battery is not low)
        val releaseConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val releaseReq = PeriodicWorkRequestBuilder<ReleaseCheckWorker>(12, TimeUnit.HOURS)
            .setConstraints(releaseConstraints)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_PERIODIC_RELEASE, ExistingPeriodicWorkPolicy.KEEP, releaseReq)
    }

    fun triggerImmediateBackgroundResearch() {
        val req = OneTimeWorkRequestBuilder<ResearchScheduleWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueue(req)
    }

    companion object {
        const val WORK_PERIODIC_RESEARCH = "zehn_periodic_research"
        const val WORK_PERIODIC_RELEASE = "zehn_periodic_release_check"
    }
}
