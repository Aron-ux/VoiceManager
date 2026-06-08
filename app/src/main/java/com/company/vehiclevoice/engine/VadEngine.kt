package com.company.vehiclevoice.engine

import com.company.vehiclevoice.audio.PcmFrame
import java.util.ArrayDeque

data class VadConfig(
    val maxListenMs: Long = 5_000L,
    val speechStartTimeoutMs: Long = 2_000L,
    val endSilenceMs: Long = 700L,
    val minSpeechMs: Long = 300L,
    val preSpeechMs: Long = 200L,
    val minSpeechRms: Double = 0.010,
    val speechStartFrames: Int = 3,
)

enum class VadEndReason {
    END_SILENCE,
    SPEECH_START_TIMEOUT,
    MAX_LISTEN,
}

data class VadSegment(
    val samples: ShortArray,
    val sampleRateHz: Int,
    val startedAtMs: Long,
    val speechStartedAtMs: Long,
    val endedAtMs: Long,
    val startedFrameSequence: Long,
    val speechStartedFrameSequence: Long,
    val endedFrameSequence: Long,
    val durationMs: Long,
    val speechMs: Long,
    val trailingSilenceMs: Long,
    val peakRms: Double,
    val reason: VadEndReason,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VadSegment

        if (!samples.contentEquals(other.samples)) return false
        if (sampleRateHz != other.sampleRateHz) return false
        if (startedAtMs != other.startedAtMs) return false
        if (speechStartedAtMs != other.speechStartedAtMs) return false
        if (endedAtMs != other.endedAtMs) return false
        if (startedFrameSequence != other.startedFrameSequence) return false
        if (speechStartedFrameSequence != other.speechStartedFrameSequence) return false
        if (endedFrameSequence != other.endedFrameSequence) return false
        if (durationMs != other.durationMs) return false
        if (speechMs != other.speechMs) return false
        if (trailingSilenceMs != other.trailingSilenceMs) return false
        if (peakRms != other.peakRms) return false
        if (reason != other.reason) return false

        return true
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRateHz
        result = 31 * result + startedAtMs.hashCode()
        result = 31 * result + speechStartedAtMs.hashCode()
        result = 31 * result + endedAtMs.hashCode()
        result = 31 * result + startedFrameSequence.hashCode()
        result = 31 * result + speechStartedFrameSequence.hashCode()
        result = 31 * result + endedFrameSequence.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + speechMs.hashCode()
        result = 31 * result + trailingSilenceMs.hashCode()
        result = 31 * result + peakRms.hashCode()
        result = 31 * result + reason.hashCode()
        return result
    }
}

sealed class VadEvent {
    data class SpeechStarted(
        val frameSequence: Long,
        val timestampMs: Long,
        val rms: Double,
        val threshold: Double,
    ) : VadEvent()

    data class SegmentReady(
        val segment: VadSegment,
    ) : VadEvent()

    data class Timeout(
        val listenedMs: Long,
        val frameSequence: Long,
        val reason: VadEndReason,
    ) : VadEvent()
}

class VadEngine(
    private val config: VadConfig = VadConfig(),
) {
    private enum class Phase {
        WAITING_FOR_SPEECH,
        RECORDING,
        DONE,
    }

    private var phase = Phase.WAITING_FOR_SPEECH
    private var listeningStartedAtMs: Long? = null
    private var noiseRms = INITIAL_NOISE_RMS
    private var speechCandidateFrames = 0
    private var candidateSpeechFrame: PcmFrame? = null
    private var speechStartedFrame: PcmFrame? = null
    private var trailingSilenceMs = 0L
    private var peakRms = 0.0
    private val preSpeechFrames = ArrayDeque<PcmFrame>()
    private val recordedFrames = ArrayList<PcmFrame>()

    fun accept(frame: PcmFrame): VadEvent? {
        if (phase == Phase.DONE) {
            return null
        }

        val startedAt = listeningStartedAtMs ?: frame.timestampMs.also {
            listeningStartedAtMs = it
        }
        val listenedMs = frame.timestampMs - startedAt + frameDurationMs(frame)
        peakRms = maxOf(peakRms, frame.rms)

        if (listenedMs >= config.maxListenMs) {
            return if (phase == Phase.RECORDING) {
                complete(frame, VadEndReason.MAX_LISTEN)
            } else {
                phase = Phase.DONE
                VadEvent.Timeout(
                    listenedMs = listenedMs,
                    frameSequence = frame.sequence,
                    reason = VadEndReason.MAX_LISTEN,
                )
            }
        }

        return when (phase) {
            Phase.WAITING_FOR_SPEECH -> acceptWaitingFrame(frame, listenedMs)
            Phase.RECORDING -> acceptRecordingFrame(frame)
            Phase.DONE -> null
        }
    }

    private fun acceptWaitingFrame(frame: PcmFrame, listenedMs: Long): VadEvent? {
        rememberPreSpeechFrame(frame)

        val threshold = speechThreshold()
        val speech = frame.rms >= threshold
        if (speech) {
            if (speechCandidateFrames == 0) {
                candidateSpeechFrame = frame
            }
            speechCandidateFrames += 1
        } else {
            updateNoiseFloor(frame.rms)
            speechCandidateFrames = 0
            candidateSpeechFrame = null
        }

        if (speechCandidateFrames >= config.speechStartFrames) {
            phase = Phase.RECORDING
            speechStartedFrame = candidateSpeechFrame ?: frame
            recordedFrames.addAll(preSpeechFrames)
            preSpeechFrames.clear()
            trailingSilenceMs = 0L

            val speechStart = speechStartedFrame ?: frame
            return VadEvent.SpeechStarted(
                frameSequence = speechStart.sequence,
                timestampMs = speechStart.timestampMs,
                rms = frame.rms,
                threshold = threshold,
            )
        }

        if (listenedMs >= config.speechStartTimeoutMs) {
            phase = Phase.DONE
            return VadEvent.Timeout(
                listenedMs = listenedMs,
                frameSequence = frame.sequence,
                reason = VadEndReason.SPEECH_START_TIMEOUT,
            )
        }

        return null
    }

    private fun acceptRecordingFrame(frame: PcmFrame): VadEvent? {
        recordedFrames += frame

        val threshold = speechThreshold()
        if (frame.rms >= threshold) {
            trailingSilenceMs = 0L
        } else {
            trailingSilenceMs += frameDurationMs(frame)
        }

        val speechStartedAt = speechStartedFrame?.timestampMs ?: frame.timestampMs
        val speechMs = frame.timestampMs - speechStartedAt + frameDurationMs(frame)
        if (speechMs >= config.minSpeechMs && trailingSilenceMs >= config.endSilenceMs) {
            return complete(frame, VadEndReason.END_SILENCE)
        }

        return null
    }

    private fun complete(frame: PcmFrame, reason: VadEndReason): VadEvent.SegmentReady {
        if (phase != Phase.RECORDING) {
            recordedFrames += frame
        }
        phase = Phase.DONE

        val firstFrame = recordedFrames.firstOrNull() ?: frame
        val speechFrame = speechStartedFrame ?: firstFrame
        val totalSamples = recordedFrames.sumOf { it.samples.size }
        val samples = ShortArray(totalSamples)
        var offset = 0
        for (recordedFrame in recordedFrames) {
            recordedFrame.samples.copyInto(samples, destinationOffset = offset)
            offset += recordedFrame.samples.size
        }

        val durationMs = samples.size * 1_000L / firstFrame.sampleRateHz
        val speechMs = frame.timestampMs - speechFrame.timestampMs + frameDurationMs(frame)
        return VadEvent.SegmentReady(
            VadSegment(
                samples = samples,
                sampleRateHz = firstFrame.sampleRateHz,
                startedAtMs = firstFrame.timestampMs,
                speechStartedAtMs = speechFrame.timestampMs,
                endedAtMs = frame.timestampMs + frameDurationMs(frame),
                startedFrameSequence = firstFrame.sequence,
                speechStartedFrameSequence = speechFrame.sequence,
                endedFrameSequence = frame.sequence,
                durationMs = durationMs,
                speechMs = speechMs,
                trailingSilenceMs = trailingSilenceMs,
                peakRms = peakRms,
                reason = reason,
            ),
        )
    }

    private fun rememberPreSpeechFrame(frame: PcmFrame) {
        preSpeechFrames += frame
        val maxFrames = maxOf(1, (config.preSpeechMs / frameDurationMs(frame)).toInt())
        while (preSpeechFrames.size > maxFrames) {
            preSpeechFrames.removeFirst()
        }
    }

    private fun speechThreshold(): Double {
        return maxOf(config.minSpeechRms, noiseRms * NOISE_MULTIPLIER + NOISE_OFFSET_RMS)
    }

    private fun updateNoiseFloor(rms: Double) {
        noiseRms = noiseRms * NOISE_EMA_RETAIN + rms * (1.0 - NOISE_EMA_RETAIN)
    }

    private fun frameDurationMs(frame: PcmFrame): Long {
        return frame.samples.size * 1_000L / frame.sampleRateHz
    }

    companion object {
        private const val INITIAL_NOISE_RMS = 0.0025
        private const val NOISE_MULTIPLIER = 3.0
        private const val NOISE_OFFSET_RMS = 0.003
        private const val NOISE_EMA_RETAIN = 0.95
    }
}
