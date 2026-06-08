package com.company.vehiclevoice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.company.vehiclevoice.audio.AudioRecorder
import com.company.vehiclevoice.audio.PcmFrame
import com.company.vehiclevoice.engine.KwsDetection
import com.company.vehiclevoice.engine.KwsEngine
import com.company.vehiclevoice.engine.KwsModelAssets

class VoiceForegroundService : Service() {

    @Volatile
    private var currentState = STATE_STOPPED
    private var audioRecorder: AudioRecorder? = null
    private var kwsEngine: KwsEngine? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastRmsBroadcastMs = 0L
    private var lastWakeDetectedMs = 0L
    private var wakeCount = 0
    private var lastWakeKeyword: String? = null

    private val returnToIdleRunnable = Runnable {
        if (currentState == STATE_LISTENING) {
            updateState(
                STATE_IDLE_KWS,
                "M3 KWS debug cycle complete. Returning to IDLE_KWS; VAD/ASR are intentionally disabled.",
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                updateState(STATE_STOPPING, "Foreground voice service is stopping.")
                stopSelf()
                START_NOT_STICKY
            }

            ACTION_START, null -> {
                startVoiceForegroundService()
                START_STICKY
            }

            else -> {
                updateState(currentState, "Unknown service action ignored: ${intent.action}")
                START_STICKY
            }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(returnToIdleRunnable)
        stopAudioRecorder()
        stopKwsEngine()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        getSystemService(NotificationManager::class.java).cancel(VOICE_NOTIFICATION_ID)
        sendStatusBroadcast(STATE_STOPPED, "Foreground voice service stopped.")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startVoiceForegroundService() {
        currentState = STATE_IDLE_KWS
        startForeground(
            VOICE_NOTIFICATION_ID,
            buildNotification("M3 service starting. Loading KWS model."),
        )

        startKwsEngine()
        startAudioRecorder()
        updateState(
            STATE_IDLE_KWS,
            if (kwsEngine?.isStarted == true) {
                "Foreground voice service started. KWS is listening for 小智小智 / 你好小智."
            } else {
                "Foreground voice service started, but KWS is not ready. Check model files/logs."
            },
        )
    }

    private fun startKwsEngine() {
        if (kwsEngine != null) {
            return
        }

        try {
            val modelDir = KwsModelAssets.ensureCopied(this)
            val engine = KwsEngine(modelDir)
            engine.start()
            kwsEngine = engine
            updateState(STATE_IDLE_KWS, "KWS model loaded from ${modelDir.absolutePath}.")
        } catch (error: Throwable) {
            val detail = error.message ?: error.javaClass.simpleName
            updateState(STATE_IDLE_KWS, "KWS model failed to load: $detail")
        }
    }

    private fun startAudioRecorder() {
        if (audioRecorder != null) {
            return
        }

        audioRecorder = AudioRecorder(
            object : AudioRecorder.Listener {
                override fun onFrame(frame: PcmFrame) {
                    maybeProcessKws(frame)
                    maybeBroadcastRms(frame)
                }

                override fun onError(message: String, error: Throwable?) {
                    val detail = error?.message?.let { "$message $it" } ?: message
                    updateState(currentState, detail)
                }
            },
        )

        val started = audioRecorder?.start() == true
        if (!started) {
            audioRecorder = null
        }
    }

    private fun stopAudioRecorder() {
        audioRecorder?.stop()
        audioRecorder = null
    }

    private fun stopKwsEngine() {
        kwsEngine?.stop()
        kwsEngine = null
    }

    private fun maybeProcessKws(frame: PcmFrame) {
        if (currentState != STATE_IDLE_KWS) {
            return
        }

        val detection = try {
            kwsEngine?.accept(frame)
        } catch (error: Throwable) {
            mainHandler.post {
                val detail = error.message ?: error.javaClass.simpleName
                updateState(currentState, "KWS decode failed: $detail")
            }
            null
        } ?: return

        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeDetectedMs < WAKE_COOLDOWN_MS) {
            kwsEngine?.reset()
            return
        }

        lastWakeDetectedMs = now
        currentState = STATE_LISTENING
        mainHandler.post { handleWakeDetected(detection) }
    }

    private fun handleWakeDetected(detection: KwsDetection) {
        wakeCount += 1
        lastWakeKeyword = detection.keyword
        mainHandler.removeCallbacks(returnToIdleRunnable)
        updateState(
            STATE_LISTENING,
            "Wake word detected: ${detection.keyword} at frame ${detection.frameSequence}.",
        )
        mainHandler.postDelayed(returnToIdleRunnable, LISTENING_DEBUG_HOLD_MS)
    }

    private fun maybeBroadcastRms(frame: PcmFrame) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRmsBroadcastMs < RMS_BROADCAST_INTERVAL_MS) {
            return
        }

        lastRmsBroadcastMs = now
        VoiceStatusBus.publish(
            VoiceStatusSnapshot(
                state = currentState,
                rms = frame.rms,
                frameSequence = frame.sequence,
                sampleRateHz = frame.sampleRateHz,
                kwsReady = kwsEngine?.isStarted == true,
                wakeKeyword = lastWakeKeyword,
                wakeCount = wakeCount,
            ),
        )

        val intent = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, currentState)
            putExtra(EXTRA_RMS, frame.rms)
            putExtra(EXTRA_FRAME_SEQUENCE, frame.sequence)
            putExtra(EXTRA_SAMPLE_RATE_HZ, frame.sampleRateHz)
            putExtra(EXTRA_KWS_READY, kwsEngine?.isStarted == true)
            putExtra(EXTRA_WAKE_KEYWORD, lastWakeKeyword)
            putExtra(EXTRA_WAKE_COUNT, wakeCount)
        }
        sendBroadcast(intent)
    }

    private fun updateState(state: String, message: String) {
        currentState = state

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(
            VOICE_NOTIFICATION_ID,
            buildNotification("Current state: $state"),
        )

        sendStatusBroadcast(state, message)
    }

    private fun sendStatusBroadcast(state: String, message: String) {
        VoiceStatusBus.publish(
            VoiceStatusSnapshot(
                state = state,
                message = message,
                kwsReady = kwsEngine?.isStarted == true,
                wakeKeyword = lastWakeKeyword,
                wakeCount = wakeCount,
            ),
        )

        val statusIntent = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_KWS_READY, kwsEngine?.isStarted == true)
            putExtra(EXTRA_WAKE_KEYWORD, lastWakeKeyword)
            putExtra(EXTRA_WAKE_COUNT, wakeCount)
        }
        sendBroadcast(statusIntent)
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopServiceIntent = PendingIntent.getService(
            this,
            1,
            createStopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_voice_service)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                R.drawable.ic_voice_service,
                getString(R.string.stop_service),
                stopServiceIntent,
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.company.vehiclevoice.action.START"
        const val ACTION_STOP = "com.company.vehiclevoice.action.STOP"
        const val ACTION_STATUS = "com.company.vehiclevoice.action.STATUS"

        const val EXTRA_STATE = "extra_state"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_RMS = "extra_rms"
        const val EXTRA_FRAME_SEQUENCE = "extra_frame_sequence"
        const val EXTRA_SAMPLE_RATE_HZ = "extra_sample_rate_hz"
        const val EXTRA_KWS_READY = "extra_kws_ready"
        const val EXTRA_WAKE_KEYWORD = "extra_wake_keyword"
        const val EXTRA_WAKE_COUNT = "extra_wake_count"

        const val STATE_UNKNOWN = "UNKNOWN"
        const val STATE_STOPPED = "STOPPED"
        const val STATE_STOPPING = "STOPPING"
        const val STATE_IDLE_KWS = "IDLE_KWS"
        const val STATE_LISTENING = "LISTENING"

        private const val CHANNEL_ID = "voice_foreground_service"
        private const val VOICE_NOTIFICATION_ID = 20260607
        private const val RMS_BROADCAST_INTERVAL_MS = 200L
        private const val WAKE_COOLDOWN_MS = 800L
        private const val LISTENING_DEBUG_HOLD_MS = 1_500L

        fun createStartIntent(context: Context): Intent {
            return Intent(context, VoiceForegroundService::class.java).apply {
                action = ACTION_START
            }
        }

        fun createStopIntent(context: Context): Intent {
            return Intent(context, VoiceForegroundService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }
}
