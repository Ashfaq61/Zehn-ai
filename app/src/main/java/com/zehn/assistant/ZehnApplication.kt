package com.zehn.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.zehn.assistant.security.CrashRecoveryManager
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltAndroidApp
class ZehnApplication : Application() {

    @Inject
    lateinit var crashRecoveryManager: CrashRecoveryManager

    override fun onCreate() {
        super.onCreate()
        installGlobalCrashLogger()
        createNotificationChannels()
    }

    private fun installGlobalCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashFile = File(filesDir, CRASH_LOG_FILE_NAME)
                val sw = StringWriter()
                PrintWriter(sw).use { pw ->
                    throwable.printStackTrace(pw)
                }
                val formattedTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
                val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"
                val androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

                val logContent = buildString {
                    appendLine("=== Zehn AI Crash Log ===")
                    appendLine("Time: $formattedTime")
                    appendLine("Device Model: $deviceModel")
                    appendLine("Android Version: $androidVersion")
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    appendLine("Stack Trace:")
                    append(sw.toString())
                }
                crashFile.writeText(logContent)
            } catch (_: Throwable) {
                // Never swallow the crash if writing crash.txt itself fails
            }

            try {
                crashRecoveryManager.recordUncaughtCrash(throwable)
            } catch (_: Throwable) {
            }

            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val wakeChannel = NotificationChannel(
                CHANNEL_WAKE_WORD,
                getString(R.string.wake_word_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps offline 'Hey Zehn' wake-word detection active"
                setShowBadge(false)
            }
            val overlayChannel = NotificationChannel(
                CHANNEL_OVERLAY,
                getString(R.string.overlay_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Floating voice orb & emergency stop controller"
                setShowBadge(false)
            }
            val triggerChannel = NotificationChannel(
                CHANNEL_TRIGGERS,
                "Zehn Automations",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications from scheduled and event-driven triggers"
            }
            manager.createNotificationChannels(listOf(wakeChannel, overlayChannel, triggerChannel))
        }
    }

    companion object {
        const val CRASH_LOG_FILE_NAME = "crash.txt"
        const val CHANNEL_WAKE_WORD = "zehn_wake_word"
        const val CHANNEL_OVERLAY = "zehn_overlay"
        const val CHANNEL_TRIGGERS = "zehn_triggers"
    }
}
