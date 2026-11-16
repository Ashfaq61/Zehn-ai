package com.zehn.assistant.upgrade

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.zehn.assistant.BuildConfig
import com.zehn.assistant.R
import com.zehn.assistant.ZehnApplication
import com.zehn.assistant.data.PreferencesRepository
import com.zehn.assistant.data.db.AutoDevRunEntity
import com.zehn.assistant.data.db.ReleaseRecordEntity
import com.zehn.assistant.data.db.UpgradeCenterDao
import com.zehn.assistant.security.KeystoreCryptoManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateCheckSummary(
    val latestRelease: ReleaseRecordEntity? = null,
    val updateAvailable: Boolean = false,
    val statusMessage: String = ""
)

/**
 * Monitors verified GitHub Releases, verifies device compatibility & trusted GitHub URLs,
 * maintains release rollback history, and posts Android notifications that open the verified
 * download page so the user completes Android's standard installation confirmation.
 */
@Singleton
class ReleaseUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val crypto: KeystoreCryptoManager,
    private val prefs: PreferencesRepository,
    private val upgradeDao: UpgradeCenterDao
) {
    suspend fun checkForVerifiedReleaseUpdate(notifyIfNewer: Boolean = true): Result<UpdateCheckSummary> = withContext(Dispatchers.IO) {
        val settings = prefs.settings.first()
        val repoSlug = settings.githubRepoSlug.trim().trim('/')
        if (!repoSlug.contains('/')) {
            return@withContext Result.failure(IllegalStateException("Configure GitHub 'owner/repo' to check releases."))
        }

        val url = "https://api.github.com/repos/$repoSlug/releases?per_page=10"
        val token = crypto.getSecret(KeystoreCryptoManager.KEY_GITHUB_TOKEN).trim()

        val req = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("User-Agent", "ZehnAI-ReleaseWatcher/4.0")
            .apply { if (token.isNotBlank()) addHeader("Authorization", "Bearer $token") }
            .get()
            .build()

        runCatching {
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("GitHub Releases HTTP ${resp.code}")
                val raw = resp.body?.string().orEmpty()
                val parsed = parseReleasesJson(raw, installedMinSdk = 24, deviceSdk = Build.VERSION.SDK_INT)

                for (rel in parsed) {
                    val existing = upgradeDao.findReleaseByTag(rel.tagName)
                    upgradeDao.upsertRelease(
                        if (existing != null) rel.copy(id = existing.id, notified = existing.notified) else rel
                    )
                }

                val newest = parsed.firstOrNull()
                if (newest == null) {
                    upgradeDao.insertDevRun(
                        AutoDevRunEntity(
                            actionType = "RELEASE_CHECK",
                            branchOrRef = repoSlug,
                            httpStatus = resp.code,
                            summary = "No published releases found yet",
                            detail = "",
                            success = true
                        )
                    )
                    return@use UpdateCheckSummary(null, false, "No published GitHub Releases found for $repoSlug.")
                }

                val newer = isVersionNewer(newest.versionName, BuildConfig.VERSION_NAME) && newest.compatibleWithDevice
                val existingNewest = upgradeDao.findReleaseByTag(newest.tagName)
                if (newer && notifyIfNewer && settings.notifyOnUpdateReady && existingNewest?.notified != true) {
                    showUpdateReadyNotification(newest)
                    upgradeDao.upsertRelease((existingNewest ?: newest).copy(notified = true))
                }

                upgradeDao.insertDevRun(
                    AutoDevRunEntity(
                        actionType = "RELEASE_CHECK",
                        branchOrRef = newest.tagName,
                        httpStatus = resp.code,
                        summary = if (newer) "Verified update ${newest.versionName} available" else "Up to date (${BuildConfig.VERSION_NAME})",
                        detail = newest.apkDownloadUrl.ifBlank { newest.releaseHtmlUrl },
                        success = true
                    )
                )

                UpdateCheckSummary(
                    latestRelease = newest,
                    updateAvailable = newer,
                    statusMessage = if (newer) {
                        "Verified update ${newest.tagName} (${formatBytes(newest.apkSizeBytes)}) ready."
                    } else {
                        "Installed v${BuildConfig.VERSION_NAME} is up to date (latest: ${newest.tagName})."
                    }
                )
            }
        }
    }

    internal fun parseReleasesJson(
        json: String,
        installedMinSdk: Int,
        deviceSdk: Int
    ): List<ReleaseRecordEntity> {
        val arr = JSONArray(json)
        val list = mutableListOf<ReleaseRecordEntity>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optBoolean("draft", false)) continue
            val tag = obj.optString("tag_name", "").trim()
            if (tag.isBlank()) continue
            val verName = tag.removePrefix("v").removePrefix("V").trim()
            val htmlUrl = obj.optString("html_url", "")
            val assets = obj.optJSONArray("assets")

            var apkName = ""
            var apkUrl = ""
            var apkSize = 0L
            if (assets != null) {
                for (a in 0 until assets.length()) {
                    val asset = assets.optJSONObject(a) ?: continue
                    val name = asset.optString("name", "")
                    val dlUrl = asset.optString("browser_download_url", "")
                    if (name.endsWith(".apk", ignoreCase = true) && isTrustedGitHubUrl(dlUrl)) {
                        apkName = name
                        apkUrl = dlUrl
                        apkSize = asset.optLong("size", 0L)
                        if (name.contains("release", ignoreCase = true)) break
                    }
                }
            }

            val compat = deviceSdk >= installedMinSdk && isTrustedGitHubUrl(htmlUrl.ifBlank { apkUrl })
            val compatNote = if (compat) "Compatible with Android API $deviceSdk (>= minSdk $installedMinSdk)" else "Blocked: untrusted URL or Android API mismatch"

            list.add(
                ReleaseRecordEntity(
                    tagName = tag,
                    versionName = verName,
                    parsedVersionCode = extractVersionCodeHint(obj.optString("body", ""), verName),
                    releaseName = obj.optString("name", tag),
                    changelog = obj.optString("body", "No release notes provided.").take(1500),
                    apkAssetName = apkName,
                    apkDownloadUrl = apkUrl,
                    releaseHtmlUrl = htmlUrl,
                    apkSizeBytes = apkSize,
                    compatibleWithDevice = compat,
                    compatibilityNote = compatNote,
                    publishedAt = obj.optString("published_at", "").take(10)
                )
            )
        }
        return list
    }

    internal fun isTrustedGitHubUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return url.startsWith("https://github.com/") ||
            url.startsWith("https://objects.githubusercontent.com/") ||
            url.startsWith("https://release-assets.githubusercontent.com/")
    }

    internal fun isVersionNewer(candidate: String, current: String): Boolean {
        val candParts = candidate.split(".", "-", "+").mapNotNull { it.filter { ch -> ch.isDigit() }.toIntOrNull() }
        val currParts = current.split(".", "-", "+").mapNotNull { it.filter { ch -> ch.isDigit() }.toIntOrNull() }
        val maxLen = maxOf(candParts.size, currParts.size)
        for (i in 0 until maxLen) {
            val c = candParts.getOrElse(i) { 0 }
            val r = currParts.getOrElse(i) { 0 }
            if (c > r) return true
            if (c < r) return false
        }
        return false
    }

    private fun extractVersionCodeHint(body: String, verName: String): Int? {
        Regex("""versionCode[:=\s]+(\d+)""", RegexOption.IGNORE_CASE).find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        val digits = verName.split(".").mapNotNull { it.toIntOrNull() }
        return if (digits.size >= 3) digits[0] * 10000 + digits[1] * 100 + digits[2] else null
    }

    fun openVerifiedUpdateInBrowser(release: ReleaseRecordEntity): Boolean {
        val targetUrl = release.apkDownloadUrl.takeIf { isTrustedGitHubUrl(it) }
            ?: release.releaseHtmlUrl.takeIf { isTrustedGitHubUrl(it) }
            ?: return false
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun showUpdateReadyNotification(release: ReleaseRecordEntity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val targetUrl = release.apkDownloadUrl.ifBlank { release.releaseHtmlUrl }
        if (!isTrustedGitHubUrl(targetUrl)) return

        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            context,
            release.tagName.hashCode(),
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sizeLabel = if (release.apkSizeBytes > 0) " • ${formatBytes(release.apkSizeBytes)}" else ""
        val notif = NotificationCompat.Builder(context, ZehnApplication.CHANNEL_UPDATES)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("Zehn AI ${release.tagName} Ready$sizeLabel")
            .setContentText("Tap Update to download & install via Android installer.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${release.changelog.take(260)}\n\nAPK: ${release.apkAssetName.ifBlank { "GitHub Release" }}$sizeLabel"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(0, "Update", pi)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_UPDATE, notif)
        }
    }

    fun showBuildFailureNotification(message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notif = NotificationCompat.Builder(context, ZehnApplication.CHANNEL_UPDATES)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("Zehn Auto-Dev Build Failed")
            .setContentText(message.take(120))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_BUILD_FAIL, notif)
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "APK"
        val mb = bytes / (1024.0 * 1024.0)
        return String.format(java.util.Locale.US, "%.1f MB", mb)
    }

    companion object {
        const val NOTIF_ID_UPDATE = 4001
        const val NOTIF_ID_BUILD_FAIL = 4002
    }
}
