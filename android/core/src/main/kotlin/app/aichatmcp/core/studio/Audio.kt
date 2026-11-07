package app.aichatmcp.core.studio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Abtastrate der Aufnahmen für Piper-Training (Stufe medium). */
const val TARGET_RATE = 22050

/** 16-Bit-Mono-PCM-WAV, die Aufnahmen der App (und die des Python-Werkzeugs). */
object Wav {
    class Data(val samples: ShortArray, val rate: Int, val channels: Int)

    fun encode(samples: ShortArray, rate: Int): ByteArray {
        val dataBytes = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(dataBytes)
        for (s in samples) buf.putShort(s)
        return buf.array()
    }

    /** Liest 16-Bit-PCM (Mehrkanal wird gemittelt). Andere Formate werfen eine [IllegalArgumentException]. */
    fun decode(bytes: ByteArray): Data {
        require(bytes.size >= 44 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "keine WAV-Datei" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var channels = 1
        var rate = TARGET_RATE
        var bits = 16
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                require(buf.getShort(body).toInt() == 1) { "nur PCM" }
                channels = buf.getShort(body + 2).toInt()
                rate = buf.getInt(body + 4)
                bits = buf.getShort(body + 14).toInt()
            } else if (id == "data") {
                require(bits == 16) { "nur 16 Bit" }
                val available = min(size.toLong() and 0xFFFFFFFFL, (bytes.size - body).toLong()).toInt()
                val frames = available / (2 * channels)
                val out = ShortArray(frames)
                for (i in 0 until frames) {
                    var sum = 0
                    for (c in 0 until channels) sum += buf.getShort(body + (i * channels + c) * 2)
                    out[i] = (sum / channels).toShort()
                }
                return Data(out, rate, channels)
            }
            pos = body + size + (size and 1)
        }
        throw IllegalArgumentException("keine Audiodaten")
    }
}

/** Kennzahlen einer Aufnahme (gleiche Definition wie `stats` im Python-Werkzeug). */
data class AudioStats(val duration: Double, val peak: Double, val rmsDb: Double, val clip: Double, val lead: Double, val tail: Double)

object AudioMath {
    fun toFloat(x: ShortArray): FloatArray = FloatArray(x.size) { x[it] / 32768f }

    fun toShort(x: FloatArray): ShortArray = ShortArray(x.size) { (x[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }

    fun db(value: Double): Double = 20.0 * log10(max(value, 1e-9))

    /**
     * Umrechnung der Abtastrate mit gefenstertem Sinc (Tiefpass knapp unter der neuen Nyquist-Frequenz): sauber genug, dass beim
     * Verkleinern (44.100 -> 22.050 Hz) nichts zurückgefaltet wird.
     */
    fun resample(x: FloatArray, rateIn: Int, rateOut: Int): FloatArray {
        if (rateIn == rateOut || x.isEmpty()) return x.copyOf()
        val ratio = rateOut.toDouble() / rateIn
        val cutoff = min(1.0, ratio) * 0.95 // relativ zur Eingangs-Nyquist-Frequenz
        val halfWidth = 16
        val scale = max(1.0, 1.0 / ratio) // Kernel beim Verkleinern strecken
        // Kernel einmal als Tabelle (symmetrisch, 256 Stützstellen je Einheit) statt sin/cos je Abgriff: sonst dauert eine Aufnahme zu lange
        val steps = 256
        val table = DoubleArray(halfWidth * steps + 2) { k ->
            val d = k.toDouble() / steps
            val arg = PI * d * cutoff
            val sinc = if (abs(arg) < 1e-9) 1.0 else sin(arg) / arg
            if (d >= halfWidth) 0.0 else sinc * 0.5 * (1 + kotlin.math.cos(PI * d / halfWidth)) // Hann-Fenster
        }
        val g = gcd(rateIn, rateOut)
        val span = Math.ceil(halfWidth * scale).toInt() + 1
        // Die Gewichte wiederholen sich nach rateOut/g Ausgabewerten (bei 44.100 -> 22.050 Hz ist es nur eine Phase): einmal je Phase rechnen
        val weights = arrayOfNulls<FloatArray>(rateOut / g)
        fun buildWeights(frac: Double): FloatArray {
            val w = DoubleArray(2 * span + 1)
            var norm = 0.0
            for (k in -span..span) {
                val pos = abs((k - frac) / scale) * steps
                if (pos >= halfWidth * steps) continue
                val idx = pos.toInt()
                val f = pos - idx
                w[k + span] = table[idx] * (1 - f) + table[idx + 1] * f
                norm += w[k + span]
            }
            return FloatArray(w.size) { (if (norm != 0.0) w[it] / norm else 0.0).toFloat() }
        }
        val n = Math.round(x.size * ratio).toInt()
        val out = FloatArray(n)
        for (i in 0 until n) {
            val num = i.toLong() * rateIn
            val base = (num / rateOut).toInt()
            val rem = (num % rateOut).toInt()
            val phase = rem / g
            val w = weights[phase] ?: buildWeights(rem.toDouble() / rateOut).also { weights[phase] = it }
            var acc = 0f
            if (base - span >= 0 && base + span < x.size) {
                for (k in 0..2 * span) acc += w[k] * x[base - span + k]
            } else {
                for (k in -span..span) {
                    val j = base + k
                    if (j in x.indices) acc += w[k + span] * x[j]
                }
            }
            out[i] = acc
        }
        return out
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    /** Erster und letzter Abschnitt (10 ms) oberhalb der Schwelle relativ zur Spitze; null bei Stille. */
    private fun activeRange(x: FloatArray, rate: Int, thresholdDb: Double): IntRange? {
        if (x.isEmpty()) return null
        var peak = 0f
        for (v in x) peak = max(peak, abs(v))
        if (peak < 1e-4f) return null
        val window = max(1, rate / 100)
        val n = x.size / window
        if (n == 0) return 0 until x.size
        val limit = peak * Math.pow(10.0, thresholdDb / 20.0)
        var first = -1
        var last = -1
        for (w in 0 until n) {
            var sum = 0.0
            for (k in 0 until window) {
                val v = x[w * window + k].toDouble()
                sum += v * v
            }
            if (sqrt(sum / window) > limit) {
                if (first < 0) first = w
                last = w
            }
        }
        if (first < 0) return null
        return (first * window) until min(x.size, (last + 1) * window)
    }

    /** Schneidet Stille vorn und hinten ab, lässt aber [marginS] Rand stehen (Atem und Ausklang sollen nicht fehlen). */
    fun trimSilence(x: FloatArray, rate: Int, thresholdDb: Double = -45.0, marginS: Double = 0.25): FloatArray {
        val range = activeRange(x, rate, thresholdDb) ?: return x
        val margin = (marginS * rate).toInt()
        return x.copyOfRange(max(0, range.first - margin), min(x.size, range.last + 1 + margin))
    }

    fun stats(x: FloatArray, rate: Int): AudioStats {
        val duration = if (rate > 0) x.size.toDouble() / rate else 0.0
        if (x.isEmpty()) return AudioStats(0.0, 0.0, -120.0, 0.0, 0.0, 0.0)
        var peak = 0.0
        var clipped = 0
        var sumAll = 0.0
        for (v in x) {
            val a = abs(v).toDouble()
            peak = max(peak, a)
            if (a >= 0.999) clipped++
            sumAll += a * a
        }
        val range = activeRange(x, rate, -40.0)
            ?: return AudioStats(duration, peak, db(sqrt(sumAll / x.size)), 0.0, duration, 0.0)
        var sum = 0.0
        for (i in range) sum += x[i].toDouble() * x[i]
        return AudioStats(
            duration, peak, db(sqrt(sum / (range.last - range.first + 1))), clipped.toDouble() / x.size,
            range.first.toDouble() / rate, (x.size - (range.last + 1)).toDouble() / rate,
        )
    }

    /** Spitze eines Blocks (0..1) für die Pegelanzeige. */
    fun peak(block: ShortArray, length: Int): Float {
        var p = 0
        for (i in 0 until length) p = max(p, abs(block[i].toInt()))
        return p / 32768f
    }
}

/** Hilfsfunktion für Tests und Export: Bytes einer WAV-Datei aus Samples. */
fun wavBytes(samples: ShortArray, rate: Int = TARGET_RATE): ByteArray = ByteArrayOutputStream().also { it.write(Wav.encode(samples, rate)) }.toByteArray()
