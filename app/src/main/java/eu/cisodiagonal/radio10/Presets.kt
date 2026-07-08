package eu.cisodiagonal.radio10

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single observable source of truth for every control. Compose reads/writes it
 * (snapshot state → auto recompose), the engine is driven from it via [applyAll],
 * and it serialises to/from JSON for presets.
 */
class UiState {
    private val N = DspEngine.NUM_BANDS

    var inputGain by mutableFloatStateOf(0f)
    var masterOn by mutableStateOf(true)
    var meterCalDb by mutableFloatStateOf(0f)
    var linkAR by mutableStateOf(false)   // link attack/release across bands

    val bandOn = mutableStateListOf(*Array(N) { true })
    val threshold = mutableStateListOf(*Array(N) { -24f })
    val attack = mutableStateListOf(*Array(N) { 20f })
    val release = mutableStateListOf(*Array(N) { 150f })
    val ratio = mutableStateListOf(*Array(N) { 4f })
    val makeup = mutableStateListOf(*Array(N) { 0f })

    var limOn by mutableStateOf(true)
    var limThr by mutableFloatStateOf(-1f)
    var limAtk by mutableFloatStateOf(1f)
    var limRel by mutableFloatStateOf(60f)
    var limRatio by mutableFloatStateOf(10f)
    var limPost by mutableFloatStateOf(0f)

    /** Push every value into the live effect + meter. */
    fun applyAll(dsp: DspEngine, meter: BandMeter) {
        dsp.setEnabled(masterOn)
        dsp.setInputGainDb(inputGain)
        meter.calibrationDb = meterCalDb
        for (b in 0 until N) {
            dsp.setBandEnabled(b, bandOn[b])
            dsp.setThreshold(b, threshold[b])
            dsp.setAttack(b, attack[b])
            dsp.setRelease(b, release[b])
            dsp.setRatio(b, ratio[b])
            dsp.setBandPostGain(b, makeup[b])
        }
        dsp.setLimiterEnabled(limOn)
        dsp.setLimiterThreshold(limThr)
        dsp.setLimiterAttack(limAtk)
        dsp.setLimiterRelease(limRel)
        dsp.setLimiterRatio(limRatio)
        dsp.setLimiterPostGain(limPost)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("inputGain", inputGain.toDouble())
        put("masterOn", masterOn)
        put("meterCalDb", meterCalDb.toDouble())
        put("linkAR", linkAR)
        put("bandOn", JSONArray(bandOn.toList()))
        put("threshold", farr(threshold)); put("attack", farr(attack))
        put("release", farr(release)); put("ratio", farr(ratio)); put("makeup", farr(makeup))
        put("limOn", limOn); put("limThr", limThr.toDouble()); put("limAtk", limAtk.toDouble())
        put("limRel", limRel.toDouble()); put("limRatio", limRatio.toDouble())
        put("limPost", limPost.toDouble())
    }

    fun fromJson(o: JSONObject) {
        inputGain = o.optDouble("inputGain", 0.0).toFloat()
        masterOn = o.optBoolean("masterOn", true)
        meterCalDb = o.optDouble("meterCalDb", 0.0).toFloat()
        linkAR = o.optBoolean("linkAR", false)
        readBool(o, "bandOn", bandOn)
        readF(o, "threshold", threshold); readF(o, "attack", attack)
        readF(o, "release", release); readF(o, "ratio", ratio); readF(o, "makeup", makeup)
        limOn = o.optBoolean("limOn", true)
        limThr = o.optDouble("limThr", -1.0).toFloat()
        limAtk = o.optDouble("limAtk", 1.0).toFloat()
        limRel = o.optDouble("limRel", 60.0).toFloat()
        limRatio = o.optDouble("limRatio", 10.0).toFloat()
        limPost = o.optDouble("limPost", 0.0).toFloat()
    }

    private fun farr(l: List<Float>) = JSONArray().apply { l.forEach { put(it.toDouble()) } }
    private fun readF(o: JSONObject, k: String, dst: MutableList<Float>) {
        val a = o.optJSONArray(k) ?: return
        for (i in 0 until minOf(a.length(), dst.size)) dst[i] = a.optDouble(i).toFloat()
    }
    private fun readBool(o: JSONObject, k: String, dst: MutableList<Boolean>) {
        val a = o.optJSONArray(k) ?: return
        for (i in 0 until minOf(a.length(), dst.size)) dst[i] = a.optBoolean(i)
    }
}

/** Named presets in SharedPreferences (one JSON blob per name). */
class PresetRepo(ctx: Context) {
    private val sp = ctx.getSharedPreferences("presets", Context.MODE_PRIVATE)

    companion object {
        /** Reserved slot holding the last live state; restored on app return. */
        const val ACTIVE = "__active__"
        /** Reserved slot naming the active preset (which one Save overwrites). */
        const val ACTIVE_NAME = "__active_name__"
        private val RESERVED = setOf(ACTIVE, ACTIVE_NAME)
    }

    /** User-named presets only — the reserved slots are hidden. */
    fun names(): List<String> = sp.all.keys.filter { it !in RESERVED }.sorted()

    /** Persist the current live state so it survives Activity/process death. */
    fun saveActive(state: UiState) = save(ACTIVE, state)

    /** Restore the last live state into [into]; false if none saved yet. */
    fun loadActive(into: UiState): Boolean = load(ACTIVE, into)

    /** The preset name the user last loaded/saved — what Save overwrites. */
    var activeName: String
        get() = sp.getString(ACTIVE_NAME, "") ?: ""
        set(v) { sp.edit().putString(ACTIVE_NAME, v).apply() }

    fun save(name: String, state: UiState) {
        sp.edit().putString(name, state.toJson().toString()).apply()
    }

    fun load(name: String, into: UiState): Boolean {
        val s = sp.getString(name, null) ?: return false
        return try { into.fromJson(JSONObject(s)); true } catch (_: Exception) { false }
    }

    fun delete(name: String) = sp.edit().remove(name).apply()
}
