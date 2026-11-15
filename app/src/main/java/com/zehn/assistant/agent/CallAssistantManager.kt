package com.zehn.assistant.agent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.zehn.assistant.data.PreferencesRepository
import com.zehn.assistant.data.db.ActionLogDao
import com.zehn.assistant.data.db.ActionLogEntity
import com.zehn.assistant.data.model.VoiceProfile
import com.zehn.assistant.voice.SentenceStreamingTtsEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallAssistantManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PreferencesRepository,
    private val tts: SentenceStreamingTtsEngine,
    private val actionLogDao: ActionLogDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _incomingCaller = MutableStateFlow<String?>(null)
    val incomingCaller: StateFlow<String?> = _incomingCaller.asStateFlow()

    private var registeredCallback: Any? = null

    fun startMonitoringIfPermitted() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && registeredCallback == null) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleCallState(state, "Incoming Caller")
                }
            }
            tm.registerTelephonyCallback(context.mainExecutor, cb)
            registeredCallback = cb
        }
    }

    private fun handleCallState(state: Int, number: String) {
        scope.launch {
            val settings = prefs.settings.first()
            if (!settings.callAssistantEnabled) return@launch
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    _incomingCaller.value = number
                    tts.speakImmediate("Incoming call from $number. Say answer or reject.", settings.activeVoice)
                    actionLogDao.insert(ActionLogEntity("VOICE", "Call Announced", number, "INFO"))
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    _incomingCaller.value = null
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun answerIncomingCall(): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            return "Grant Answer Phone Calls permission in Permissions to answer by voice."
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            @Suppress("DEPRECATION")
            telecom?.acceptRingingCall()
            return "Answered incoming call."
        }
        return "Voice call answer requires Android 8.0+."
    }

    @SuppressLint("MissingPermission")
    fun rejectIncomingCall(sendAutoReply: Boolean = false, number: String? = null): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            return "Grant Answer Phone Calls permission to reject calls."
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            @Suppress("DEPRECATION")
            telecom?.endCall()
            if (sendAutoReply && !number.isNullOrBlank() &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
            ) {
                scope.launch {
                    val reply = prefs.settings.first().autoReplySmsText
                    val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.getSystemService(SmsManager::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        SmsManager.getDefault()
                    }
                    sms?.sendTextMessage(number, null, reply, null, null)
                }
            }
            return "Call rejected."
        }
        return "Voice call rejection requires Android 9.0+."
    }
}
