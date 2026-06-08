package com.company.vehiclevoice.engine

import com.company.vehiclevoice.audio.PcmFrame
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

data class KwsDetection(
    val keyword: String,
    val frameSequence: Long,
    val timestampMs: Long,
)

class KwsEngine(
    private val modelDir: File,
) {
    private var keywordSpotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null

    val isStarted: Boolean
        get() = keywordSpotter != null && stream != null

    fun start() {
        if (isStarted) {
            return
        }

        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE_HZ,
                featureDim = FEATURE_DIM,
                dither = 0.0f,
            ),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelDir, ENCODER).absolutePath,
                    decoder = File(modelDir, DECODER).absolutePath,
                    joiner = File(modelDir, JOINER).absolutePath,
                ),
                tokens = File(modelDir, TOKENS).absolutePath,
                numThreads = NUM_THREADS,
                debug = false,
                provider = "cpu",
                modelType = "zipformer2",
            ),
            maxActivePaths = MAX_ACTIVE_PATHS,
            keywordsFile = File(modelDir, KEYWORDS).absolutePath,
            keywordsScore = KEYWORDS_SCORE,
            keywordsThreshold = KEYWORDS_THRESHOLD,
            numTrailingBlanks = NUM_TRAILING_BLANKS,
        )

        val spotter = KeywordSpotter(assetManager = null, config = config)
        keywordSpotter = spotter
        stream = spotter.createStream()
    }

    fun accept(frame: PcmFrame): KwsDetection? {
        val spotter = keywordSpotter ?: return null
        val activeStream = stream ?: return null

        activeStream.acceptWaveform(frame.toFloatSamples(), frame.sampleRateHz)
        while (spotter.isReady(activeStream)) {
            spotter.decode(activeStream)
        }

        val keyword = spotter.getResult(activeStream).keyword
        if (keyword.isBlank()) {
            return null
        }

        spotter.reset(activeStream)
        return KwsDetection(
            keyword = keyword,
            frameSequence = frame.sequence,
            timestampMs = frame.timestampMs,
        )
    }

    fun reset() {
        val spotter = keywordSpotter ?: return
        val activeStream = stream ?: return
        spotter.reset(activeStream)
    }

    fun stop() {
        stream?.release()
        stream = null
        keywordSpotter?.release()
        keywordSpotter = null
    }

    private fun PcmFrame.toFloatSamples(): FloatArray {
        return FloatArray(samples.size) { index ->
            samples[index] / PCM_NORMALIZER
        }
    }

    companion object {
        private const val SAMPLE_RATE_HZ = 16_000
        private const val FEATURE_DIM = 80
        private const val NUM_THREADS = 2
        private const val MAX_ACTIVE_PATHS = 4
        private const val KEYWORDS_SCORE = 2.0f
        private const val KEYWORDS_THRESHOLD = 0.35f
        private const val NUM_TRAILING_BLANKS = 1
        private const val PCM_NORMALIZER = 32768.0f

        private const val ENCODER = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val DECODER = "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val JOINER = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val TOKENS = "tokens.txt"
        private const val KEYWORDS = "keywords.txt"
    }
}
