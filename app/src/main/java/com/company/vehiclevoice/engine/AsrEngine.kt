package com.company.vehiclevoice.engine

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File

data class AsrResult(
    val text: String,
    val elapsedMs: Long,
    val sampleCount: Int,
    val audioDurationMs: Long,
)

class AsrEngine(
    private val modelDir: File,
) {
    private val lock = Any()
    private var recognizer: OfflineRecognizer? = null

    val isStarted: Boolean
        get() = synchronized(lock) { recognizer != null }

    fun start() {
        synchronized(lock) {
            if (recognizer != null) {
                return
            }

            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(
                    sampleRate = SAMPLE_RATE_HZ,
                    featureDim = FEATURE_DIM,
                    dither = 0.0f,
                ),
                modelConfig = OfflineModelConfig(
                    paraformer = OfflineParaformerModelConfig(
                        model = File(modelDir, MODEL).absolutePath,
                    ),
                    tokens = File(modelDir, TOKENS).absolutePath,
                    numThreads = NUM_THREADS,
                    debug = false,
                    provider = "cpu",
                    modelType = "paraformer",
                ),
                decodingMethod = "greedy_search",
            )

            recognizer = OfflineRecognizer(assetManager = null, config = config)
        }
    }

    fun recognize(segment: VadSegment): AsrResult {
        synchronized(lock) {
            val activeRecognizer = recognizer ?: error("ASR engine is not started.")
            val samples = segment.samples.toFloatSamples()
            val startMs = android.os.SystemClock.elapsedRealtime()
            val stream = activeRecognizer.createStream()
            return try {
                stream.acceptWaveform(samples, segment.sampleRateHz)
                activeRecognizer.decode(stream)
                val text = activeRecognizer.getResult(stream).text.trim()
                val elapsedMs = android.os.SystemClock.elapsedRealtime() - startMs
                AsrResult(
                    text = text,
                    elapsedMs = elapsedMs,
                    sampleCount = segment.samples.size,
                    audioDurationMs = segment.durationMs,
                )
            } finally {
                stream.release()
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            recognizer?.release()
            recognizer = null
        }
    }

    private fun ShortArray.toFloatSamples(): FloatArray {
        return FloatArray(size) { index ->
            this[index] / PCM_NORMALIZER
        }
    }

    companion object {
        private const val SAMPLE_RATE_HZ = 16_000
        private const val FEATURE_DIM = 80
        private const val NUM_THREADS = 2
        private const val PCM_NORMALIZER = 32768.0f
        private const val MODEL = "model.int8.onnx"
        private const val TOKENS = "tokens.txt"
    }
}
