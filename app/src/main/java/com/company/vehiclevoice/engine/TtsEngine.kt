package com.company.vehiclevoice.engine

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class TtsEngine(
    context: Context,
    private val listener: Listener,
) : TextToSpeech.OnInitListener {

    interface Listener {
        fun onReady(message: String)
        fun onInitFailed(message: String)
        fun onPlaybackStarted(utteranceId: String)
        fun onPlaybackCompleted(utteranceId: String)
        fun onPlaybackFailed(utteranceId: String, message: String)
    }

    @Volatile
    var isReady: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val enginePackage = selectEnginePackage(context.applicationContext)
    private val textToSpeech = if (enginePackage.isNullOrBlank()) {
        TextToSpeech(context.applicationContext, this)
    } else {
        TextToSpeech(context.applicationContext, this, enginePackage)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            isReady = false
            notifyListener { onInitFailed("TextToSpeech init failed: status=$status.") }
            return
        }

        textToSpeech.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        textToSpeech.setSpeechRate(1.0f)
        textToSpeech.setPitch(1.0f)
        textToSpeech.setOnUtteranceProgressListener(createProgressListener())

        val languageStatus = textToSpeech.setLanguage(Locale.CHINA)
        if (
            languageStatus == TextToSpeech.LANG_MISSING_DATA ||
            languageStatus == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            isReady = false
            notifyListener { onInitFailed("TextToSpeech Chinese voice unavailable: status=$languageStatus.") }
            return
        }

        isReady = true
        val engineDetail = enginePackage?.let { " engine=$it" }.orEmpty()
        notifyListener { onReady("TextToSpeech initialized with Locale.CHINA.$engineDetail") }
    }

    fun speak(text: String): String? {
        if (!isReady || text.isBlank()) {
            return null
        }

        val utteranceId = "m7_tts_${SystemClock.elapsedRealtime()}"
        val result = textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
        return if (result == TextToSpeech.SUCCESS) utteranceId else null
    }

    fun shutdown() {
        isReady = false
        textToSpeech.stop()
        textToSpeech.shutdown()
    }

    private fun createProgressListener(): UtteranceProgressListener {
        return object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                notifyListener { onPlaybackStarted(utteranceId) }
            }

            override fun onDone(utteranceId: String) {
                notifyListener { onPlaybackCompleted(utteranceId) }
            }

            @Deprecated("Deprecated in Android TTS API but still called by older engines.")
            override fun onError(utteranceId: String) {
                notifyListener { onPlaybackFailed(utteranceId, "TextToSpeech playback failed.") }
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                notifyListener { onPlaybackFailed(utteranceId, "TextToSpeech playback failed: code=$errorCode.") }
            }
        }
    }

    private fun notifyListener(block: Listener.() -> Unit) {
        mainHandler.post {
            listener.block()
        }
    }

    private fun selectEnginePackage(context: Context): String? {
        val defaultEngine = Settings.Secure.getString(
            context.contentResolver,
            "tts_default_synth",
        )?.takeIf { it.isNotBlank() }

        val engines = context.packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
            0,
        )
        if (defaultEngine != null && engines.any { it.serviceInfo.packageName == defaultEngine }) {
            return defaultEngine
        }

        return engines.firstOrNull()?.serviceInfo?.packageName
    }
}
