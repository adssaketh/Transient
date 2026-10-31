package com.demonlab.lune.tools.eq

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One filter of an AutoEq "ParametricEQ.txt" profile (the EqualizerAPO text format). */
class AutoEqFilter(val type: Type, val frequencyHz: Double, val gainDb: Double, val q: Double) {
    enum class Type { PEAK, LOW_SHELF, HIGH_SHELF }
}

/** A headphone correction made by AutoEq: a preamp (always negative or zero) and up to [AutoEqProfile.MAX_FILTERS] filters. */
class AutoEqProfile(val name: String, val preampDb: Double, val filters: List<AutoEqFilter>) {
    companion object {
        const val MAX_FILTERS = 20

        // "Preamp: -5.1 dB"
        private val PREAMP = Regex("""^\s*Preamp\s*:\s*(-?\d+(?:\.\d+)?)\s*dB""", RegexOption.IGNORE_CASE)

        // "Filter 2: ON PK Fc 6867 Hz Gain 5.9 dB Q 1.01"   (type PK = peak, LSC / HSC = low / high shelf)
        private val FILTER = Regex(
            """^\s*Filter\s*\d*\s*:\s*(ON|OFF)\s+([A-Za-z]+)\s+Fc\s+(\d+(?:\.\d+)?)\s*Hz(?:\s+Gain\s+(-?\d+(?:\.\d+)?)\s*dB)?(?:\s+Q\s+(\d+(?:\.\d+)?))?""",
            RegexOption.IGNORE_CASE
        )

        /** Reads the text of a profile. Returns null when it holds no usable filter. Lines it does not know are skipped. */
        fun parse(text: String, name: String): AutoEqProfile? {
            var preamp = 0.0
            val filters = ArrayList<AutoEqFilter>()
            for (line in text.lineSequence()) {
                val pre = PREAMP.find(line)
                if (pre != null) {
                    preamp = pre.groupValues[1].toDouble().coerceIn(-30.0, 0.0)
                    continue
                }
                val m = FILTER.find(line) ?: continue
                if (m.groupValues[1].equals("OFF", ignoreCase = true)) continue
                val type = when (m.groupValues[2].uppercase()) {
                    "PK", "PEQ" -> AutoEqFilter.Type.PEAK
                    "LSC", "LS" -> AutoEqFilter.Type.LOW_SHELF
                    "HSC", "HS" -> AutoEqFilter.Type.HIGH_SHELF
                    else -> continue
                }
                if (filters.size >= MAX_FILTERS) break
                val frequency = m.groupValues[3].toDouble().coerceIn(20.0, 20000.0)
                val gain = (m.groupValues[4].ifEmpty { "0" }).toDouble().coerceIn(-24.0, 24.0)
                val q = (m.groupValues[5].ifEmpty { "0.707" }).toDouble().coerceIn(0.1, 30.0)
                filters.add(AutoEqFilter(type, frequency, gain, q))
            }
            return if (filters.isEmpty()) null else AutoEqProfile(name, preamp, filters)
        }
    }
}

/** One second-order filter, divided by a0: y = b0*x + z1; z1 = b1*x - a1*y + z2; z2 = b2*x - a2*y. */
class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    /** Level of this filter at [frequencyHz], in dB. */
    fun magnitudeDb(frequencyHz: Double, sampleRate: Int): Double {
        val w = 2.0 * PI * frequencyHz / sampleRate
        val c1 = cos(w)
        val s1 = sin(w)
        val c2 = cos(2.0 * w)
        val s2 = sin(2.0 * w)
        // numerator and denominator at z = e^(jw), with z^-1 = c1 - j*s1 and z^-2 = c2 - j*s2
        val nr = b0 + b1 * c1 + b2 * c2
        val ni = -(b1 * s1 + b2 * s2)
        val dr = 1.0 + a1 * c1 + a2 * c2
        val di = -(a1 * s1 + a2 * s2)
        val numerator = nr * nr + ni * ni
        val denominator = dr * dr + di * di
        return 10.0 * kotlin.math.log10(max(numerator, 1e-30) / max(denominator, 1e-30))
    }

    companion object {
        /**
         * The RBJ "audio EQ cookbook" filter for [filter]. AutoEq's shelf types (LSC / HSC) are these shelves with a Q.
         * [gainScale] shrinks or grows the gain (1.0 = as in the profile). Returns null when the filter changes nothing.
         */
        fun design(filter: AutoEqFilter, gainScale: Double, sampleRate: Int): Biquad? {
            val gainDb = filter.gainDb * gainScale
            if (abs(gainDb) < 0.005) return null
            val frequency = min(filter.frequencyHz, sampleRate * 0.45)
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * frequency / sampleRate
            val cosW = cos(w0)
            val alpha = sin(w0) / (2.0 * filter.q)
            val b0: Double
            val b1: Double
            val b2: Double
            val a0: Double
            val a1: Double
            val a2: Double
            when (filter.type) {
                AutoEqFilter.Type.PEAK -> {
                    b0 = 1.0 + alpha * a
                    b1 = -2.0 * cosW
                    b2 = 1.0 - alpha * a
                    a0 = 1.0 + alpha / a
                    a1 = -2.0 * cosW
                    a2 = 1.0 - alpha / a
                }
                AutoEqFilter.Type.LOW_SHELF -> {
                    val sq = 2.0 * sqrt(a) * alpha
                    b0 = a * ((a + 1.0) - (a - 1.0) * cosW + sq)
                    b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW)
                    b2 = a * ((a + 1.0) - (a - 1.0) * cosW - sq)
                    a0 = (a + 1.0) + (a - 1.0) * cosW + sq
                    a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW)
                    a2 = (a + 1.0) + (a - 1.0) * cosW - sq
                }
                AutoEqFilter.Type.HIGH_SHELF -> {
                    val sq = 2.0 * sqrt(a) * alpha
                    b0 = a * ((a + 1.0) + (a - 1.0) * cosW + sq)
                    b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW)
                    b2 = a * ((a + 1.0) + (a - 1.0) * cosW - sq)
                    a0 = (a + 1.0) - (a - 1.0) * cosW + sq
                    a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW)
                    a2 = (a + 1.0) - (a - 1.0) * cosW - sq
                }
            }
            return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }
    }
}

/**
 * A profile turned into running filters for one stream: a given sample rate and channel count. Not thread safe: one thread
 * (the playback thread) uses it. Changing the profile or the strength means building a new chain.
 */
class AutoEqChain(profile: AutoEqProfile, strengthPercent: Int, val sampleRate: Int, val channels: Int) {
    private val stages: Array<Biquad>
    private val b0: DoubleArray
    private val b1: DoubleArray
    private val b2: DoubleArray
    private val a1: DoubleArray
    private val a2: DoubleArray
    private val z1: DoubleArray
    private val z2: DoubleArray

    /** Linear gain of the preamp. */
    val preampGain: Double

    /** The preamp in dB (for the trace). */
    val preampDb: Double

    init {
        val scale = strengthPercent.coerceIn(0, 100) / 100.0
        stages = profile.filters.mapNotNull { Biquad.design(it, scale, sampleRate) }.toTypedArray()
        b0 = DoubleArray(stages.size) { stages[it].b0 }
        b1 = DoubleArray(stages.size) { stages[it].b1 }
        b2 = DoubleArray(stages.size) { stages[it].b2 }
        a1 = DoubleArray(stages.size) { stages[it].a1 }
        a2 = DoubleArray(stages.size) { stages[it].a2 }
        z1 = DoubleArray(stages.size * channels)
        z2 = DoubleArray(stages.size * channels)

        // The highest point of the whole curve decides how much room the preamp must make so that the music cannot clip.
        var peakDb = -100.0
        val top = min(20000.0, sampleRate * 0.45)
        for (i in 0 until 240) {
            val frequency = 20.0 * (top / 20.0).pow(i / 239.0)
            var level = 0.0
            for (stage in stages) level += stage.magnitudeDb(frequency, sampleRate)
            peakDb = max(peakDb, level)
        }
        val safeLimit = if (peakDb > 0.0) -peakDb - 0.05 else 0.0
        preampDb = min(profile.preampDb * scale, safeLimit)
        preampGain = 10.0.pow(preampDb / 20.0)
    }

    /** True when the profile changes nothing at this strength. */
    val isEmpty: Boolean get() = stages.isEmpty()

    /** Forgets the filters' memory (after a seek or a new track), so an old tail cannot leak into the new sound. */
    fun reset() {
        z1.fill(0.0)
        z2.fill(0.0)
    }

    /** One sample (-1..1) of channel [channel] through the preamp and all filters. */
    fun process(sample: Double, channel: Int): Double {
        var x = sample * preampGain
        val n = stages.size
        var i = 0
        while (i < n) {
            val k = i * channels + channel
            val y = b0[i] * x + z1[k]
            z1[k] = b1[i] * x - a1[i] * y + z2[k]
            z2[k] = b2[i] * x - a2[i] * y
            x = y
            i++
        }
        return x
    }
}
