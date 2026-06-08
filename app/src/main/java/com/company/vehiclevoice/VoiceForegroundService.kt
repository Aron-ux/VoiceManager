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
import android.util.Log
import com.company.vehiclevoice.action.VoiceAction
import com.company.vehiclevoice.audio.AudioRecorder
import com.company.vehiclevoice.audio.PcmFrame
import com.company.vehiclevoice.data.MockVehicleStateRepository
import com.company.vehiclevoice.data.VehicleStateSnapshot
import com.company.vehiclevoice.engine.AsrEngine
import com.company.vehiclevoice.engine.AsrModelAssets
import com.company.vehiclevoice.engine.AsrResult
import com.company.vehiclevoice.engine.KwsDetection
import com.company.vehiclevoice.engine.KwsEngine
import com.company.vehiclevoice.engine.KwsModelAssets
import com.company.vehiclevoice.engine.TtsEngine
import com.company.vehiclevoice.engine.VadEndReason
import com.company.vehiclevoice.engine.VadEngine
import com.company.vehiclevoice.engine.VadEvent
import com.company.vehiclevoice.engine.VadSegment
import com.company.vehiclevoice.nlu.IntentResult
import com.company.vehiclevoice.nlu.RuleIntentParser
import com.company.vehiclevoice.response.ResponseTemplateEngine

class VoiceForegroundService : Service() {

    @Volatile
    private var currentState = STATE_STOPPED
    private var audioRecorder: AudioRecorder? = null
    private var kwsEngine: KwsEngine? = null
    private var asrEngine: AsrEngine? = null
    private var ttsEngine: TtsEngine? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastRmsBroadcastMs = 0L
    private var lastWakeDetectedMs = 0L
    private var wakeCount = 0
    private var lastWakeKeyword: String? = null
    private var vadEngine: VadEngine? = null
    private var vadSegmentCount = 0
    private var lastVadSegment: VadSegment? = null
    private var lastVadReason: VadEndReason? = null
    private var asrCount = 0
    private var asrRunning = false
    private var lastAsrText = ""
    private var lastAsrElapsedMs = 0L
    private val intentParser = RuleIntentParser()
    private val vehicleStateRepository = MockVehicleStateRepository()
    private val responseTemplateEngine = ResponseTemplateEngine()
    private var lastIntentResult: IntentResult? = null
    private var lastVehicleState: VehicleStateSnapshot? = null
    private var lastVoiceActionJson = ""
    private var ttsCount = 0
    private var ttsPlaying = false
    private var lastTtsText = ""
    private var lastTtsUtteranceId: String? = null

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
        vadEngine = null
        stopAudioRecorder()
        stopKwsEngine()
        stopAsrEngine()
        stopTtsEngine()
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
            buildNotification("M7 service starting. Loading KWS/ASR/TTS."),
        )

        startKwsEngine()
        startAsrEngine()
        startTtsEngine()
        startAudioRecorder()
        updateState(
            STATE_IDLE_KWS,
            if (kwsEngine?.isStarted == true && asrEngine?.isStarted == true) {
                "Foreground voice service started. M7 template response and Android TTS are initializing."
            } else {
                "Foreground voice service started, but KWS or ASR is not ready. Check model files/logs."
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
                    maybeProcessVad(frame)
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

    private fun startAsrEngine() {
        if (asrEngine != null) {
            return
        }

        try {
            val modelDir = AsrModelAssets.ensureCopied(this)
            val engine = AsrEngine(modelDir)
            engine.start()
            asrEngine = engine
            updateState(STATE_IDLE_KWS, "ASR model loaded from ${modelDir.absolutePath}.")
        } catch (error: Throwable) {
            val detail = error.message ?: error.javaClass.simpleName
            updateState(STATE_IDLE_KWS, "ASR model failed to load: $detail")
        }
    }

    private fun stopAsrEngine() {
        asrEngine?.stop()
        asrEngine = null
    }

    private fun startTtsEngine() {
        if (ttsEngine != null) {
            return
        }

        ttsEngine = TtsEngine(
            this,
            object : TtsEngine.Listener {
                override fun onReady(message: String) {
                    updateState(currentState, message)
                }

                override fun onInitFailed(message: String) {
                    updateState(currentState, "$message M7 will keep generating text responses without playback.")
                }

                override fun onPlaybackStarted(utteranceId: String) {
                    if (utteranceId != lastTtsUtteranceId || currentState == STATE_STOPPING) {
                        return
                    }

                    ttsPlaying = true
                    updateState(STATE_TTS_PLAYING, "TTS playback started: '$lastTtsText'.")
                }

                override fun onPlaybackCompleted(utteranceId: String) {
                    if (utteranceId != lastTtsUtteranceId || currentState == STATE_STOPPING) {
                        return
                    }

                    ttsPlaying = false
                    kwsEngine?.reset()
                    updateState(STATE_IDLE_KWS, "TTS playback completed. Returning to IDLE_KWS.")
                }

                override fun onPlaybackFailed(utteranceId: String, message: String) {
                    if (utteranceId != lastTtsUtteranceId || currentState == STATE_STOPPING) {
                        return
                    }

                    ttsPlaying = false
                    kwsEngine?.reset()
                    updateState(STATE_IDLE_KWS, "$message Returning to IDLE_KWS.")
                }
            },
        )
        updateState(currentState, "Android TextToSpeech engine is initializing.")
    }

    private fun stopTtsEngine() {
        ttsPlaying = false
        ttsEngine?.shutdown()
        ttsEngine = null
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
        vadEngine = VadEngine()
        lastVadReason = null
        updateState(
            STATE_LISTENING,
            "Wake word detected: ${detection.keyword} at frame ${detection.frameSequence}. VAD listening started.",
        )
    }

    private fun maybeProcessVad(frame: PcmFrame) {
        if (currentState != STATE_LISTENING) {
            return
        }

        val event = vadEngine?.accept(frame) ?: return
        mainHandler.post {
            when (event) {
                is VadEvent.SpeechStarted -> handleVadSpeechStarted(event)
                is VadEvent.SegmentReady -> handleVadSegmentReady(event.segment)
                is VadEvent.Timeout -> handleVadTimeout(event)
            }
        }
    }

    private fun handleVadSpeechStarted(event: VadEvent.SpeechStarted) {
        if (currentState != STATE_LISTENING) {
            return
        }

        updateState(
            STATE_LISTENING,
            "VAD speech started at frame ${event.frameSequence}; rms=${"%.4f".format(event.rms)}, threshold=${"%.4f".format(event.threshold)}.",
        )
    }

    private fun handleVadSegmentReady(segment: VadSegment) {
        if (currentState != STATE_LISTENING) {
            return
        }

        vadSegmentCount += 1
        lastVadSegment = segment
        lastVadReason = segment.reason
        vadEngine = null
        kwsEngine?.reset()
        updateState(
            STATE_VAD_END,
            "VAD segment ready: reason=${segment.reason}, duration=${segment.durationMs}ms, speech=${segment.speechMs}ms, trailingSilence=${segment.trailingSilenceMs}ms, samples=${segment.samples.size}.",
        )
        runAsr(segment)
    }

    private fun handleVadTimeout(event: VadEvent.Timeout) {
        if (currentState != STATE_LISTENING) {
            return
        }

        lastVadReason = event.reason
        vadEngine = null
        kwsEngine?.reset()
        updateState(
            STATE_IDLE_KWS,
            "VAD ended without valid speech: reason=${event.reason}, listened=${event.listenedMs}ms at frame ${event.frameSequence}. Returning to IDLE_KWS.",
        )
    }

    private fun runAsr(segment: VadSegment) {
        val engine = asrEngine
        if (engine == null || !engine.isStarted) {
            updateState(STATE_IDLE_KWS, "ASR is not ready. Returning to IDLE_KWS.")
            return
        }

        asrRunning = true
        updateState(STATE_ASR_RUNNING, "ASR started for ${segment.samples.size} samples.")
        Thread({
            val result = runCatching {
                engine.recognize(segment)
            }

            mainHandler.post {
                asrRunning = false
                result
                    .onSuccess { handleAsrResult(it) }
                    .onFailure { error ->
                        val detail = error.message ?: error.javaClass.simpleName
                        updateState(STATE_IDLE_KWS, "ASR failed: $detail. Returning to IDLE_KWS.")
                    }
            }
        }, "VehicleAsrRecognizer").apply {
            isDaemon = true
            start()
        }
    }

    private fun handleAsrResult(result: AsrResult) {
        asrCount += 1
        lastAsrText = result.text.ifBlank { "<empty>" }
        lastAsrElapsedMs = result.elapsedMs
        kwsEngine?.reset()
        updateState(
            STATE_ASR_DONE,
            "ASR result #$asrCount: '$lastAsrText' in ${result.elapsedMs}ms for ${result.audioDurationMs}ms audio.",
        )
        handleIntentAndState(lastAsrText)
    }

    private fun handleIntentAndState(asrText: String) {
        updateState(STATE_INTENT_PARSE, "Parsing ASR text with rule intent parser.")
        val intentResult = intentParser.parse(asrText)
        lastIntentResult = intentResult

        updateState(
            STATE_STATE_QUERY,
            "Intent=${intentResult.intentId}, confidence=${"%.2f".format(intentResult.confidence)}, normalized='${intentResult.normalizedText}'.",
        )

        val state = vehicleStateRepository.getState(intentResult.requiredStateKeys)
        lastVehicleState = state
        lastVoiceActionJson = if (intentResult.matched) {
            VoiceAction.from(asrText, intentResult, state).toJson()
        } else {
            ""
        }

        if (lastVoiceActionJson.isNotBlank()) {
            Log.i(LOG_TAG, "mock_unity_action=$lastVoiceActionJson")
        }

        val responseText = responseTemplateEngine.render(intentResult, state)
        lastTtsText = responseText
        updateState(
            STATE_TEMPLATE_RESPONSE,
            "M7 response template: intent=${intentResult.intentId}, text='$responseText'.",
        )
        speakResponse(responseText)
    }

    private fun speakResponse(responseText: String) {
        val engine = ttsEngine
        if (engine == null || !engine.isReady) {
            ttsPlaying = false
            kwsEngine?.reset()
            updateState(
                STATE_IDLE_KWS,
                "TTS is not ready. Response text='$responseText'. Returning to IDLE_KWS.",
            )
            return
        }

        val utteranceId = engine.speak(responseText)
        if (utteranceId == null) {
            ttsPlaying = false
            kwsEngine?.reset()
            updateState(
                STATE_IDLE_KWS,
                "TextToSpeech rejected response text='$responseText'. Returning to IDLE_KWS.",
            )
            return
        }

        ttsCount += 1
        ttsPlaying = true
        lastTtsUtteranceId = utteranceId
        updateState(
            STATE_TTS_PLAYING,
            "M7 TTS response #$ttsCount queued: '$responseText'.",
        )
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
                vadActive = vadEngine != null,
                vadSegmentCount = vadSegmentCount,
                vadLastReason = lastVadReason?.name,
                vadLastDurationMs = lastVadSegment?.durationMs,
                vadLastSpeechMs = lastVadSegment?.speechMs,
                vadLastSamples = lastVadSegment?.samples?.size,
                asrReady = asrEngine?.isStarted == true,
                asrRunning = asrRunning,
                asrText = lastAsrText,
                asrCount = asrCount,
                asrLastElapsedMs = lastAsrElapsedMs,
                intentId = lastIntentResult?.intentId,
                intentConfidence = lastIntentResult?.confidence,
                normalizedText = lastIntentResult?.normalizedText,
                mockStateText = lastVehicleState?.toDisplayText(),
                voiceActionJson = lastVoiceActionJson,
                ttsReady = ttsEngine?.isReady == true,
                ttsPlaying = ttsPlaying,
                ttsText = lastTtsText,
                ttsCount = ttsCount,
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
            putExtra(EXTRA_VAD_ACTIVE, vadEngine != null)
            putExtra(EXTRA_VAD_SEGMENT_COUNT, vadSegmentCount)
            putExtra(EXTRA_VAD_LAST_REASON, lastVadReason?.name)
            putExtra(EXTRA_VAD_LAST_DURATION_MS, lastVadSegment?.durationMs ?: 0L)
            putExtra(EXTRA_VAD_LAST_SPEECH_MS, lastVadSegment?.speechMs ?: 0L)
            putExtra(EXTRA_VAD_LAST_SAMPLES, lastVadSegment?.samples?.size ?: 0)
            putExtra(EXTRA_ASR_READY, asrEngine?.isStarted == true)
            putExtra(EXTRA_ASR_RUNNING, asrRunning)
            putExtra(EXTRA_ASR_TEXT, lastAsrText)
            putExtra(EXTRA_ASR_COUNT, asrCount)
            putExtra(EXTRA_ASR_LAST_ELAPSED_MS, lastAsrElapsedMs)
            putExtra(EXTRA_INTENT_ID, lastIntentResult?.intentId)
            putExtra(EXTRA_INTENT_CONFIDENCE, lastIntentResult?.confidence ?: 0.0)
            putExtra(EXTRA_NORMALIZED_TEXT, lastIntentResult?.normalizedText)
            putExtra(EXTRA_MOCK_STATE_TEXT, lastVehicleState?.toDisplayText())
            putExtra(EXTRA_VOICE_ACTION_JSON, lastVoiceActionJson)
            putExtra(EXTRA_TTS_READY, ttsEngine?.isReady == true)
            putExtra(EXTRA_TTS_PLAYING, ttsPlaying)
            putExtra(EXTRA_TTS_TEXT, lastTtsText)
            putExtra(EXTRA_TTS_COUNT, ttsCount)
        }
        sendBroadcast(intent)
    }

    private fun updateState(state: String, message: String) {
        currentState = state
        Log.i(LOG_TAG, "state=$state $message")

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
                vadActive = vadEngine != null,
                vadSegmentCount = vadSegmentCount,
                vadLastReason = lastVadReason?.name,
                vadLastDurationMs = lastVadSegment?.durationMs,
                vadLastSpeechMs = lastVadSegment?.speechMs,
                vadLastSamples = lastVadSegment?.samples?.size,
                asrReady = asrEngine?.isStarted == true,
                asrRunning = asrRunning,
                asrText = lastAsrText,
                asrCount = asrCount,
                asrLastElapsedMs = lastAsrElapsedMs,
                intentId = lastIntentResult?.intentId,
                intentConfidence = lastIntentResult?.confidence,
                normalizedText = lastIntentResult?.normalizedText,
                mockStateText = lastVehicleState?.toDisplayText(),
                voiceActionJson = lastVoiceActionJson,
                ttsReady = ttsEngine?.isReady == true,
                ttsPlaying = ttsPlaying,
                ttsText = lastTtsText,
                ttsCount = ttsCount,
            ),
        )

        val statusIntent = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_KWS_READY, kwsEngine?.isStarted == true)
            putExtra(EXTRA_WAKE_KEYWORD, lastWakeKeyword)
            putExtra(EXTRA_WAKE_COUNT, wakeCount)
            putExtra(EXTRA_VAD_ACTIVE, vadEngine != null)
            putExtra(EXTRA_VAD_SEGMENT_COUNT, vadSegmentCount)
            putExtra(EXTRA_VAD_LAST_REASON, lastVadReason?.name)
            putExtra(EXTRA_VAD_LAST_DURATION_MS, lastVadSegment?.durationMs ?: 0L)
            putExtra(EXTRA_VAD_LAST_SPEECH_MS, lastVadSegment?.speechMs ?: 0L)
            putExtra(EXTRA_VAD_LAST_SAMPLES, lastVadSegment?.samples?.size ?: 0)
            putExtra(EXTRA_ASR_READY, asrEngine?.isStarted == true)
            putExtra(EXTRA_ASR_RUNNING, asrRunning)
            putExtra(EXTRA_ASR_TEXT, lastAsrText)
            putExtra(EXTRA_ASR_COUNT, asrCount)
            putExtra(EXTRA_ASR_LAST_ELAPSED_MS, lastAsrElapsedMs)
            putExtra(EXTRA_INTENT_ID, lastIntentResult?.intentId)
            putExtra(EXTRA_INTENT_CONFIDENCE, lastIntentResult?.confidence ?: 0.0)
            putExtra(EXTRA_NORMALIZED_TEXT, lastIntentResult?.normalizedText)
            putExtra(EXTRA_MOCK_STATE_TEXT, lastVehicleState?.toDisplayText())
            putExtra(EXTRA_VOICE_ACTION_JSON, lastVoiceActionJson)
            putExtra(EXTRA_TTS_READY, ttsEngine?.isReady == true)
            putExtra(EXTRA_TTS_PLAYING, ttsPlaying)
            putExtra(EXTRA_TTS_TEXT, lastTtsText)
            putExtra(EXTRA_TTS_COUNT, ttsCount)
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
        const val EXTRA_VAD_ACTIVE = "extra_vad_active"
        const val EXTRA_VAD_SEGMENT_COUNT = "extra_vad_segment_count"
        const val EXTRA_VAD_LAST_REASON = "extra_vad_last_reason"
        const val EXTRA_VAD_LAST_DURATION_MS = "extra_vad_last_duration_ms"
        const val EXTRA_VAD_LAST_SPEECH_MS = "extra_vad_last_speech_ms"
        const val EXTRA_VAD_LAST_SAMPLES = "extra_vad_last_samples"
        const val EXTRA_ASR_READY = "extra_asr_ready"
        const val EXTRA_ASR_RUNNING = "extra_asr_running"
        const val EXTRA_ASR_TEXT = "extra_asr_text"
        const val EXTRA_ASR_COUNT = "extra_asr_count"
        const val EXTRA_ASR_LAST_ELAPSED_MS = "extra_asr_last_elapsed_ms"
        const val EXTRA_INTENT_ID = "extra_intent_id"
        const val EXTRA_INTENT_CONFIDENCE = "extra_intent_confidence"
        const val EXTRA_NORMALIZED_TEXT = "extra_normalized_text"
        const val EXTRA_MOCK_STATE_TEXT = "extra_mock_state_text"
        const val EXTRA_VOICE_ACTION_JSON = "extra_voice_action_json"
        const val EXTRA_TTS_READY = "extra_tts_ready"
        const val EXTRA_TTS_PLAYING = "extra_tts_playing"
        const val EXTRA_TTS_TEXT = "extra_tts_text"
        const val EXTRA_TTS_COUNT = "extra_tts_count"

        const val STATE_UNKNOWN = "UNKNOWN"
        const val STATE_STOPPED = "STOPPED"
        const val STATE_STOPPING = "STOPPING"
        const val STATE_IDLE_KWS = "IDLE_KWS"
        const val STATE_LISTENING = "LISTENING"
        const val STATE_VAD_END = "VAD_END"
        const val STATE_ASR_RUNNING = "ASR_RUNNING"
        const val STATE_ASR_DONE = "ASR_DONE"
        const val STATE_INTENT_PARSE = "INTENT_PARSE"
        const val STATE_STATE_QUERY = "STATE_QUERY"
        const val STATE_TEMPLATE_RESPONSE = "TEMPLATE_RESPONSE"
        const val STATE_TTS_PLAYING = "TTS_PLAYING"

        private const val CHANNEL_ID = "voice_foreground_service"
        private const val VOICE_NOTIFICATION_ID = 20260607
        private const val RMS_BROADCAST_INTERVAL_MS = 200L
        private const val WAKE_COOLDOWN_MS = 800L
        private const val LOG_TAG = "VehicleVoice"

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
