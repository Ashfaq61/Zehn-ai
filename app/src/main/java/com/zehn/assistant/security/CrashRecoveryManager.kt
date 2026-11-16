package com.zehn.assistant.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class CrashRecoveryReport(
    val exceptionType: String,
    val sanitizedMessage: String,
    val timestampMs: Long
)

/**
 * Local privacy-preserving crash recovery guard.
 * Captures uncaught crashes without telemetry or leaking API keys/tokens,
 * then surfaces a recoverable notice on the next launch.
 */
@Singleton
class CrashRecoveryManager @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _lastCrashReport = MutableStateFlow(loadPendingReport())
    val lastCrashReport: StateFlow<CrashRecoveryReport?> = _lastCrashReport.asStateFlow()

    private var installed = false
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun install() {
        if (installed) return
        installed = true
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            recordUncaughtCrash(throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun recordUncaughtCrash(throwable: Throwable) {
        val type = throwable::class.java.simpleName.ifBlank { "Exception" }
        val sanitized = sanitize(throwable.message ?: "Unexpected runtime error")
        val now = System.currentTimeMillis()
        prefs.edit()
            .putBoolean(KEY_HAS_CRASH, true)
            .putString(KEY_TYPE, type)
            .putString(KEY_MESSAGE, sanitized)
            .putLong(KEY_TIMESTAMP, now)
            .commit()
        _lastCrashReport.value = CrashRecoveryReport(type, sanitized, now)
    }

    fun dismissCrashReport() {
        prefs.edit().putBoolean(KEY_HAS_CRASH, false).apply()
        _lastCrashReport.value = null
    }

    private fun loadPendingReport(): CrashRecoveryReport? {
        if (!prefs.getBoolean(KEY_HAS_CRASH, false)) return null
        val type = prefs.getString(KEY_TYPE, "Exception") ?: "Exception"
        val msg = prefs.getString(KEY_MESSAGE, "Recovered from previous error") ?: "Recovered from previous error"
        val ts = prefs.getLong(KEY_TIMESTAMP, System.currentTimeMillis())
        return CrashRecoveryReport(type, msg, ts)
    }

    internal fun sanitize(raw: String): String {
        return raw
            .replace(Regex("""AIza[0-9A-Za-z\-_]{15,}"""), "[REDACTED_KEY]")
            .replace(Regex("""sk-[0-9A-Za-z\-_]{10,}"""), "[REDACTED_KEY]")
            .replace(Regex("""(ghp|gho|ghu|ghs|github_pat)_[0-9A-Za-z_]{8,}"""), "[REDACTED_TOKEN]")
            .replace(Regex("""(gsk|hf)_[0-9A-Za-z_\-]{6,}"""), "[REDACTED_KEY]")
            .replace(Regex("""Bearer\s+[0-9A-Za-z\-._~+/]+=*""", RegexOption.IGNORE_CASE), "Bearer [REDACTED]")
            .take(220)
    }

    companion object {
        private const val PREFS_NAME = "zehn_crash_recovery"
        private const val KEY_HAS_CRASH = "has_unacknowledged_crash"
        private const val KEY_TYPE = "crash_type"
        private const val KEY_MESSAGE = "crash_message"
        private const val KEY_TIMESTAMP = "crash_timestamp"
    }
}
