package com.company.vehiclevoice.audio

data class PcmFrame(
    val samples: ShortArray,
    val sampleRateHz: Int,
    val sequence: Long,
    val timestampMs: Long,
) {
    val rms: Double = calculateRms(samples)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PcmFrame

        if (!samples.contentEquals(other.samples)) return false
        if (sampleRateHz != other.sampleRateHz) return false
        if (sequence != other.sequence) return false
        if (timestampMs != other.timestampMs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRateHz
        result = 31 * result + sequence.hashCode()
        result = 31 * result + timestampMs.hashCode()
        return result
    }

    companion object {
        private fun calculateRms(samples: ShortArray): Double {
            if (samples.isEmpty()) {
                return 0.0
            }

            var sumSquares = 0.0
            for (sample in samples) {
                val normalized = sample / Short.MAX_VALUE.toDouble()
                sumSquares += normalized * normalized
            }
            return kotlin.math.sqrt(sumSquares / samples.size)
        }
    }
}

