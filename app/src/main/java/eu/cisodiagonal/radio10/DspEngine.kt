package eu.cisodiagonal.radio10

import android.media.audiofx.DynamicsProcessing
import android.util.Log

/**
 * 5-band compressor / limiter built on android.media.audiofx.DynamicsProcessing.
 *
 * Signal chain per channel:
 *   input gain -> (preEq off) -> 5-band MBC -> (postEq off) -> limiter -> out
 *
 * Attached to the GLOBAL output mix session (audioSession = 0) for system-wide
 * processing. Some OEM ROMs restrict global-session effects; if construction
 * fails, fall back to a per-player session id via [attach].
 */
class DspEngine {

    companion object {
        const val TAG = "DspEngine"
        const val NUM_BANDS = 5
        const val GLOBAL_SESSION = 0          // AUDIO_SESSION_OUTPUT_MIX
        const val CHANNELS = 2                  // stereo; applied to all channels

        // Default 5-band crossover upper cutoffs (Hz). Band N covers
        // (cutoff[N-1] .. cutoff[N]]. Last band runs to Nyquist.
        val DEFAULT_CUTOFFS = floatArrayOf(150f, 600f, 2_500f, 7_000f, 20_000f)
    }

    private var dp: DynamicsProcessing? = null
    var session: Int = GLOBAL_SESSION
        private set

    // Cached per-band params, read by the GR meter (the effect exposes no GR).
    private val thresholdDb = FloatArray(NUM_BANDS) { -24f }
    private val ratio = FloatArray(NUM_BANDS) { 4f }
    private val kneeDb = FloatArray(NUM_BANDS) { 0f }
    private val bandOn = BooleanArray(NUM_BANDS) { true }

    /** Lower/upper crossover edge (Hz) for [band]. */
    fun bandLow(band: Int): Float = if (band == 0) 20f else DEFAULT_CUTOFFS[band - 1]
    fun bandHigh(band: Int): Float = DEFAULT_CUTOFFS[band]

    /**
     * Static estimated gain reduction (dB, positive) for [band] given an
     * input [levelDb] (dBFS), using the soft-knee downward-compression curve.
     * Detector ballistics (attack/release) are applied by the meter UI.
     */
    fun estimateGrDb(band: Int, levelDb: Float): Float {
        if (!bandOn[band]) return 0f
        val t = thresholdDb[band]
        val r = ratio[band].coerceAtLeast(1f)
        val w = kneeDb[band].coerceAtLeast(0f)
        val over = levelDb - t
        val slope = 1f - 1f / r
        return when {
            2f * over < -w -> 0f
            w > 0f && 2f * kotlin.math.abs(over) <= w -> {
                val x = over + w / 2f
                slope * x * x / (2f * w)
            }
            else -> slope * over
        }.coerceAtLeast(0f)
    }

    val isAttached: Boolean get() = dp != null

    /** Build the effect on [audioSession]. Returns true on success. */
    fun attach(audioSession: Int = GLOBAL_SESSION): Boolean {
        release()
        session = audioSession
        return try {
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                CHANNELS,
                /* preEqInUse   */ false, /* preEqBandCount  */ 0,
                /* mbcInUse     */ true,  /* mbcBandCount    */ NUM_BANDS,
                /* postEqInUse  */ false, /* postEqBandCount */ 0,
                /* limiterInUse */ true
            ).build()

            dp = DynamicsProcessing(/* priority */ 0, audioSession, cfg).apply {
                setInputGainAllChannelsTo(0f)
                enabled = true
            }
            applyDefaultCutoffs()
            Log.i(TAG, "Attached to session=$audioSession")
            true
        } catch (e: Exception) {
            Log.e(TAG, "attach failed on session=$audioSession", e)
            dp = null
            false
        }
    }

    fun release() {
        try { dp?.release() } catch (_: Exception) {}
        dp = null
    }

    /** Master on/off without tearing the effect down. */
    fun setEnabled(on: Boolean) { dp?.enabled = on }

    fun isEnabled(): Boolean = dp?.enabled == true

    // ---- Input gain ----------------------------------------------------

    /** Pre-everything input gain, dB. Wide range, e.g. -40..+40. */
    fun setInputGainDb(db: Float) {
        dp?.setInputGainAllChannelsTo(db)
    }

    // ---- Per-band compressor params ------------------------------------

    /** band: 0..NUM_BANDS-1. threshold dBFS (e.g. -100..0). */
    fun setThreshold(band: Int, dbfs: Float) {
        thresholdDb[band] = dbfs
        editBand(band) { it.threshold = dbfs }
    }

    /** attack ms. Wide: 0..1000+. */
    fun setAttack(band: Int, ms: Float) = editBand(band) { it.attackTime = ms }

    /** release ms. Wide: 0..5000+. */
    fun setRelease(band: Int, ms: Float) = editBand(band) { it.releaseTime = ms }

    fun setRatio(band: Int, r: Float) {
        ratio[band] = r
        editBand(band) { it.ratio = r }
    }

    fun setKnee(band: Int, db: Float) {
        kneeDb[band] = db
        editBand(band) { it.kneeWidth = db }
    }

    /** Per-band makeup gain, dB. */
    fun setBandPostGain(band: Int, db: Float) = editBand(band) { it.postGain = db }

    fun setBandEnabled(band: Int, on: Boolean) {
        bandOn[band] = on
        editBand(band) { it.isEnabled = on }
    }

    fun setCutoff(band: Int, hz: Float) = editBand(band) { it.cutoffFrequency = hz }

    private fun applyDefaultCutoffs() {
        for (b in 0 until NUM_BANDS) setCutoff(b, DEFAULT_CUTOFFS[b])
    }

    private inline fun editBand(band: Int, edit: (DynamicsProcessing.MbcBand) -> Unit) {
        val d = dp ?: return
        for (ch in 0 until CHANNELS) {
            val mb = d.getMbcBandByChannelIndex(ch, band)
            edit(mb)
            d.setMbcBandByChannelIndex(ch, band, mb)
        }
    }

    // ---- Output limiter ------------------------------------------------

    fun setLimiterEnabled(on: Boolean) = editLimiter { it.isEnabled = on }
    fun setLimiterThreshold(dbfs: Float) = editLimiter { it.threshold = dbfs }
    fun setLimiterAttack(ms: Float) = editLimiter { it.attackTime = ms }
    fun setLimiterRelease(ms: Float) = editLimiter { it.releaseTime = ms }
    fun setLimiterRatio(ratio: Float) = editLimiter { it.ratio = ratio }
    fun setLimiterPostGain(db: Float) = editLimiter { it.postGain = db }

    private inline fun editLimiter(edit: (DynamicsProcessing.Limiter) -> Unit) {
        val d = dp ?: return
        for (ch in 0 until CHANNELS) {
            val lim = d.getLimiterByChannelIndex(ch)
            edit(lim)
            d.setLimiterByChannelIndex(ch, lim)
        }
    }
}
