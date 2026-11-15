package com.zehn.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.zehn.assistant.security.CrashRecoveryManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ZehnApplication : Application() {

    @Inject
    lateinit var crashRecoveryManager: CrashRecoveryManager

    override fun onCreate() {
        super.onCreate()
        crashRecoveryManager.install()
        createNotificationChannels()
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
        const val CHANNEL_WAKE_WORD = "zehn_wake_word"
        const val CHANNEL_OVERLAY = "zehn_overlay"
        const val CHANNEL_TRIGGERS = "zehn_triggers"
    }
}
