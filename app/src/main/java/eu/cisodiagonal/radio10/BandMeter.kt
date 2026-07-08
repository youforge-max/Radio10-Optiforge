package eu.cisodiagonal.radio10

import android.media.audiofx.Visualizer
import android.util.Log
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Per-band gain-reduction estimator.
 *
 * DynamicsProcessing exposes no live metering, so we tap the same audio
 * session with a [Visualizer], FFT the output mix, fold the bins into the
 * 5 crossover bands to estimate each band's input level (dBFS), then run that
 * level through [DspEngine.estimateGrDb]. Attack/release-style smoothing is
 * applied so the meter moves like a real GR meter.
 *
 * Requires RECORD_AUDIO. The level is an estimate (post-mix magnitudes, not
 * the compressor's internal detector), good for visual feedback, not metrology.
 */
class BandMeter(private val dsp: DspEngine) {

    companion object {
        const val TAG = "BandMeter"
        const val SPECTRUM_BARS = 32
    }

    private var vis: Visualizer? = null

    /** Smoothed GR per band, dB (positive = reduction). Read from UI. */
    val grDb = FloatArray(DspEngine.NUM_BANDS)

    /** Log-spaced spectrum bars, normalised 0..1. Read from UI. */
    val spectrum = FloatArray(SPECTRUM_BARS)

    /** Level calibration offset (dB) added to estimated band level. Tune on-device. */
    @Volatile var calibrationDb: Float = 0f

    // meter ballistics (rise fast, fall slow)
    private val attackCoef = 0.6f
    private val releaseCoef = 0.15f

    fun start(): Boolean {
        stop()
        return try {
            vis = Visualizer(dsp.session).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]   // max, e.g. 1024
                val rate = Visualizer.getMaxCaptureRate()
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer, wf: ByteArray, sr: Int) {}
                    override fun onFftDataCapture(v: Visualizer, fft: ByteArray, sr: Int) {
                        process(fft, sr)
                    }
                }, rate, /* waveform */ false, /* fft */ true)
                enabled = true
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Visualizer start failed", e)
            vis = null
            false
        }
    }

    fun stop() {
        try { vis?.enabled = false; vis?.release() } catch (_: Exception) {}
        vis = null
        grDb.fill(0f)
    }

    /**
     * fft layout: [0]=DC real, [1]=Nyquist real, then pairs (re,im) for bins
     * 1..n/2-1. samplingRate sr is in milliHz.
     */
    private fun process(fft: ByteArray, sr: Int) {
        val n = fft.size                      // = 2 * (captureSize/2) bins worth
        val bins = n / 2
        val nyquist = (sr / 1000f) / 2f       // Hz
        if (nyquist <= 0f) return
        val binHz = nyquist / bins

        val energy = FloatArray(DspEngine.NUM_BANDS)
        val count = IntArray(DspEngine.NUM_BANDS)

        for (i in 1 until bins) {
            val re = fft[2 * i].toFloat()
            val im = fft[2 * i + 1].toFloat()
            val mag = hypot(re, im)
            val freq = i * binHz
            val band = bandFor(freq)
            if (band >= 0) { energy[band] += mag * mag; count[band]++ }
        }

        for (b in 0 until DspEngine.NUM_BANDS) {
            val rms = if (count[b] > 0) kotlin.math.sqrt(energy[b] / count[b]) else 0f
            // byte magnitude ref ~128 → rough dBFS, plus user calibration
            val levelDb = (if (rms > 0f) 20f * log10(rms / 128f) else -120f) + calibrationDb
            val target = dsp.estimateGrDb(b, levelDb)
            val coef = if (target > grDb[b]) attackCoef else releaseCoef
            grDb[b] += coef * (target - grDb[b])
        }

        computeSpectrum(fft, bins, binHz)
    }

    /**
     * Fold FFT bins into log-spaced bars normalised over [-60, 0] dB.
     *
     * Bar-centric, not bin-centric: FFT bins are LINEARLY spaced (~binHz apart),
     * so at the low end a log bar can be narrower than one bin and would catch
     * zero bins — that's why low bands looked empty. For each bar we sum the bins
     * inside its [f0,f1) range; when none fall in, we sample the nearest bin so the
     * bar still reflects real energy instead of dropping to silence.
     */
    private fun computeSpectrum(fft: ByteArray, bins: Int, binHz: Float) {
        if (binHz <= 0f) return
        val fMin = 20f
        val fMax = bins * binHz
        if (fMax <= fMin) return
        val logMin = log10(fMin)
        val logMax = log10(fMax)
        val span = logMax - logMin

        fun magSq(i: Int): Float {
            val re = fft[2 * i].toFloat(); val im = fft[2 * i + 1].toFloat()
            return re * re + im * im
        }

        for (k in 0 until SPECTRUM_BARS) {
            // log-spaced frequency edges for this bar
            val f0 = Math.pow(10.0, (logMin + span * k / SPECTRUM_BARS).toDouble()).toFloat()
            val f1 = Math.pow(10.0, (logMin + span * (k + 1) / SPECTRUM_BARS).toDouble()).toFloat()
            var acc = 0f
            var cnt = 0
            var i = (f0 / binHz).toInt().coerceAtLeast(1)
            while (i < bins && i * binHz < f1) {
                acc += magSq(i); cnt++; i++
            }
            val rms = if (cnt > 0) {
                kotlin.math.sqrt(acc / cnt)
            } else {
                // bar narrower than bin spacing (low end): sample nearest bin
                val center = kotlin.math.sqrt(f0 * f1)
                val bin = (center / binHz).roundToInt().coerceIn(1, bins - 1)
                kotlin.math.sqrt(magSq(bin))
            }
            val db = if (rms > 0f) 20f * log10(rms / 128f) + calibrationDb else -120f
            val norm = ((db + 60f) / 60f).coerceIn(0f, 1f)
            // smooth bars too
            spectrum[k] += (if (norm > spectrum[k]) 0.7f else 0.2f) * (norm - spectrum[k])
        }
    }

    private fun bandFor(freq: Float): Int {
        for (b in 0 until DspEngine.NUM_BANDS) {
            if (freq > dsp.bandLow(b) && freq <= dsp.bandHigh(b)) return b
        }
        return -1
    }
}
