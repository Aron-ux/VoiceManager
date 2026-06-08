package com.company.vehiclevoice

import android.os.Handler
import android.os.Looper

data class VoiceStatusSnapshot(
    val state: String,
    val message: String? = null,
    val rms: Double? = null,
    val frameSequence: Long? = null,
    val sampleRateHz: Int? = null,
    val kwsReady: Boolean? = null,
    val wakeKeyword: String? = null,
    val wakeCount: Int? = null,
)

object VoiceStatusBus {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var listener: ((VoiceStatusSnapshot) -> Unit)? = null

    @Volatile
    private var latestSnapshot: VoiceStatusSnapshot? = null

    fun setListener(listener: ((VoiceStatusSnapshot) -> Unit)?) {
        this.listener = listener
        val snapshot = latestSnapshot
        if (listener != null && snapshot != null) {
            mainHandler.post { listener(snapshot) }
        }
    }

    fun publish(snapshot: VoiceStatusSnapshot) {
        latestSnapshot = snapshot
        mainHandler.post {
            listener?.invoke(snapshot)
        }
    }
}
