package com.company.vehiclevoice.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorder(
    private val listener: Listener,
) {
    interface Listener {
        fun onFrame(frame: PcmFrame)
        fun onError(message: String, error: Throwable? = null)
    }

    private val running = AtomicBoolean(false)
    private var workerThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    val sampleRateHz: Int = SAMPLE_RATE_HZ
    val frameSamples: Int = FRAME_SAMPLES
    val frameDurationMs: Int = FRAME_DURATION_MS

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) {
            return true
        }

        val record = createAudioRecord()
        if (record == null) {
            running.set(false)
            listener.onError("AudioRecord init failed for 16 kHz mono PCM16.")
            return false
        }

        audioRecord = record
        workerThread = Thread({ readLoop(record) }, "VehicleAudioRecorder").apply {
            isDaemon = true
            start()
        }

        return true
    }

    fun stop() {
        if (!running.getAndSet(false)) {
            return
        }

        audioRecord?.let { record ->
            runCatching { record.stop() }
        }
        workerThread?.join(STOP_JOIN_TIMEOUT_MS)
        workerThread = null
        audioRecord = null
    }

    private fun readLoop(record: AudioRecord) {
        try {
            record.startRecording()

            val buffer = ShortArray(FRAME_SAMPLES)
            var sequence = 0L

            while (running.get()) {
                val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                    if (!running.get()) {
                        break
                    }
                    listener.onError("AudioRecord read failed with code $read.")
                    break
                }

                if (read <= 0) {
                    continue
                }

                val samples = if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read)
                listener.onFrame(
                    PcmFrame(
                        samples = samples,
                        sampleRateHz = SAMPLE_RATE_HZ,
                        sequence = sequence++,
                        timestampMs = SystemClock.elapsedRealtime(),
                    ),
                )
            }
        } catch (error: SecurityException) {
            listener.onError("Missing microphone permission for AudioRecord.", error)
        } catch (error: IllegalStateException) {
            listener.onError("AudioRecord could not start recording.", error)
        } finally {
            running.set(false)
            runCatching { record.stop() }
            record.release()
        }
    }

    private fun createAudioRecord(): AudioRecord? {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferBytes <= 0) {
            return null
        }

        val bufferBytes = maxOf(minBufferBytes, FRAME_SAMPLES * BYTES_PER_SAMPLE * BUFFER_FRAME_COUNT)
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE_HZ)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        return buildRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, audioFormat, bufferBytes)
            ?: buildRecord(MediaRecorder.AudioSource.MIC, audioFormat, bufferBytes)
    }

    private fun buildRecord(source: Int, audioFormat: AudioFormat, bufferBytes: Int): AudioRecord? {
        return runCatching {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferBytes)
                .build()
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        }.getOrNull()
    }

    companion object {
        private const val SAMPLE_RATE_HZ = 16_000
        private const val FRAME_DURATION_MS = 20
        private const val FRAME_SAMPLES = SAMPLE_RATE_HZ * FRAME_DURATION_MS / 1_000
        private const val BYTES_PER_SAMPLE = 2
        private const val BUFFER_FRAME_COUNT = 8
        private const val STOP_JOIN_TIMEOUT_MS = 500L
    }
}
