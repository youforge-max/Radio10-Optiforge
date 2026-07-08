package eu.cisodiagonal.radio10

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlin.math.roundToInt

/**
 * Radio 10 MP3 player with a built-in Optiforge 5-band compressor/limiter.
 *
 * The [PlaybackService] owns the stream + audio graph; this Activity binds to it,
 * drives the DSP from [UiState], and renders player + processing controls. When
 * the service (re)attaches the effect it bumps `sessionEpoch`; we re-push all
 * params and restart the meter on every bump.
 */
class MainActivity : ComponentActivity() {

    private val service = mutableStateOf<PlaybackService?>(null)
    private val micGranted = mutableStateOf(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service.value = (binder as PlaybackService.LocalBinder).service
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service.value = null
        }
    }

    private val askMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        micGranted.value = granted
        if (granted) service.value?.meter?.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        micGranted.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!micGranted.value) askMic.launch(Manifest.permission.RECORD_AUDIO)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    val svc = service.value
                    if (svc == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Connecting…")
                        }
                    } else {
                        PlayerScreen(svc, micGranted.value)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, PlaybackService::class.java), connection, Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        super.onStop()
        runCatching { unbindService(connection) }
        service.value = null
    }
}

@Composable
private fun PlayerScreen(svc: PlaybackService, micGranted: Boolean) {
    val ctx = LocalContext.current
    val repo = remember { PresetRepo(ctx) }
    // Restore the last live state at init so it's ready before applyAll pushes
    // it to the DSP. This composition is recreated whenever we rebind to the
    // service (app switch), so loading here — not defaults — keeps the profile.
    val st = remember { UiState().also { repo.loadActive(it) } }
    val state by svc.state.collectAsState()
    val epoch by svc.sessionEpoch.collectAsState()

    // Re-push all DSP params + restart meter every time the effect (re)attaches.
    LaunchedEffect(epoch) {
        st.applyAll(svc.dsp, svc.meter)
        if (micGranted) svc.meter.start()
    }

    // Auto-persist the live state (debounced) so an app switch or process death
    // can restore exactly what was set — no manual reload of the active profile.
    LaunchedEffect(Unit) {
        snapshotFlow { st.toJson().toString() }
            .debounce(400)
            .collect { repo.saveActive(st) }
    }

    // Poll smoothed GR + spectrum ~20 fps.
    val gr = remember { mutableStateListOf(*Array(DspEngine.NUM_BANDS) { 0f }) }
    val spectrum = remember { mutableStateListOf(*Array(BandMeter.SPECTRUM_BARS) { 0f }) }
    LaunchedEffect(Unit) {
        while (true) {
            for (b in 0 until DspEngine.NUM_BANDS) gr[b] = svc.meter.grDb[b]
            for (k in 0 until BandMeter.SPECTRUM_BARS) spectrum[k] = svc.meter.spectrum[k]
            delay(50)
        }
    }

    fun startService(station: Station) {
        val intent = Intent(ctx, PlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
        svc.play(station)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Radio 10 · Optiforge", fontSize = 22.sp, fontWeight = FontWeight.Bold)

        // ---- Player card ----
        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Station selector
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stations.ALL.forEach { s ->
                        val sel = state.stationId == s.id
                        if (sel) {
                            Button(onClick = { startService(s) }, modifier = Modifier.weight(1f)) {
                                Text(s.name, maxLines = 1)
                            }
                        } else {
                            OutlinedButton(onClick = { startService(s) }, modifier = Modifier.weight(1f)) {
                                Text(s.name, maxLines = 1)
                            }
                        }
                    }
                }

                // Now playing
                Text(
                    when {
                        state.error != null -> state.error!!
                        state.buffering -> "Connecting… (mirror ${state.urlIndex + 1}/${state.urlCount})"
                        state.playing -> state.title.ifEmpty { "▶ Live" }
                        else -> "Stopped"
                    },
                    fontSize = 15.sp,
                    color = if (state.error != null) MaterialTheme.colorScheme.error else Color(0xFFDDDDDD)
                )

                // Transport
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val current = Stations.ALL.firstOrNull { it.id == state.stationId } ?: Stations.DANCE
                    if (state.playing || state.buffering) {
                        Button(onClick = { svc.stopPlayback() }) { Text("■ Stop") }
                    } else {
                        Button(onClick = { startService(current) }) { Text("▶ Play") }
                    }
                    Text(
                        "MP3 128 kbps · ${state.urlCount} mirrors (failover)",
                        fontSize = 12.sp, color = Color(0xFF999999)
                    )
                }
            }
        }

        Text("Optiforge — 5-Band Compressor / Limiter", fontSize = 16.sp, fontWeight = FontWeight.Bold)

        if (!micGranted) {
            Text("Mic permission denied — meters/spectrum disabled. Processing still works.",
                color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }

        PresetBar(st) { st.applyAll(svc.dsp, svc.meter) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = st.masterOn, onCheckedChange = {
                st.masterOn = it; svc.dsp.setEnabled(it)
            })
            Spacer(Modifier.width(8.dp))
            Text(if (st.masterOn) "Processing ON" else "Processing OFF")
        }

        Card {
            Column(Modifier.padding(12.dp)) {
                Text("Spectrum", fontWeight = FontWeight.Bold)
                Spectrum(spectrum)
                Spacer(Modifier.height(8.dp))
                SliderRow("Meter calibration", st.meterCalDb, -40f, 40f, "dB") {
                    st.meterCalDb = it; svc.meter.calibrationDb = it
                }
            }
        }

        Card {
            Column(Modifier.padding(12.dp)) {
                Text("Input Gain", fontWeight = FontWeight.Bold)
                SliderRow("Gain", st.inputGain, -40f, 40f, "dB") {
                    st.inputGain = it; svc.dsp.setInputGainDb(it)
                }
            }
        }

        Card {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = st.linkAR, onCheckedChange = { st.linkAR = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Link attack/release across bands", fontWeight = FontWeight.Bold)
                }
            }
        }

        for (b in 0 until DspEngine.NUM_BANDS) BandCard(svc.dsp, st, b, gr[b], st.linkAR)

        LimiterCard(svc.dsp, st)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PresetBar(st: UiState, onLoaded: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { PresetRepo(ctx) }
    var names by remember { mutableStateOf(repo.names()) }
    // The active preset = the one Save overwrites; restored across launches.
    var selected by remember {
        mutableStateOf(repo.activeName.ifEmpty { names.firstOrNull() ?: "" })
    }
    var newName by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (selected.isEmpty()) "Presets" else "Preset · active: $selected",
                fontWeight = FontWeight.Bold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { expanded = true }) {
                        Text(if (selected.isEmpty()) "— none —" else selected)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        names.forEach { n ->
                            DropdownMenuItem(text = { Text(n) }, onClick = {
                                selected = n; expanded = false
                            })
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Button(enabled = selected.isNotEmpty(), onClick = {
                    if (repo.load(selected, st)) {
                        repo.activeName = selected   // now the active preset
                        repo.saveActive(st)
                        onLoaded()
                    }
                }) { Text("Load") }
                Spacer(Modifier.width(4.dp))
                OutlinedButton(enabled = selected.isNotEmpty(), onClick = {
                    repo.delete(selected)
                    names = repo.names()
                    selected = names.firstOrNull() ?: ""
                    repo.activeName = selected
                }) { Text("Del") }
            }
            // Save overwrites the currently active preset in place.
            Button(
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    repo.save(selected, st)
                    repo.activeName = selected
                    repo.saveActive(st)
                }
            ) { Text(if (selected.isEmpty()) "Save" else "Save (update \"$selected\")") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it },
                    label = { Text("Save as new") }, singleLine = true, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(enabled = newName.isNotBlank(), onClick = {
                    val nm = newName.trim()
                    repo.save(nm, st)
                    names = repo.names()
                    selected = nm
                    repo.activeName = nm         // new preset becomes active
                    repo.saveActive(st)
                    newName = ""
                }) { Text("Save") }
            }
        }
    }
}

@Composable
private fun Spectrum(bars: List<Float>) {
    Canvas(Modifier.fillMaxWidth().height(96.dp).background(Color(0xFF111111))) {
        val n = bars.size
        if (n == 0) return@Canvas
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val h = bars[i].coerceIn(0f, 1f) * size.height
            val x = i * (w + gap)
            val c = when {
                bars[i] > 0.8f -> Color(0xFFE53935)
                bars[i] > 0.5f -> Color(0xFFFFB300)
                else -> Color(0xFF43A047)
            }
            drawRect(color = c, topLeft = androidx.compose.ui.geometry.Offset(x, size.height - h),
                size = androidx.compose.ui.geometry.Size(w, h))
        }
    }
}

@Composable
private fun BandCard(dsp: DspEngine, st: UiState, band: Int, grDb: Float, linkAR: Boolean) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Band ${band + 1}  (${dsp.bandLow(band).roundToInt()}–${dsp.bandHigh(band).roundToInt()} Hz)",
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                )
                Switch(checked = st.bandOn[band], onCheckedChange = {
                    st.bandOn[band] = it; dsp.setBandEnabled(band, it)
                })
            }
            GrMeter(grDb)
            SliderRow("Threshold", st.threshold[band], -100f, 0f, "dBFS") {
                st.threshold[band] = it; dsp.setThreshold(band, it)
            }
            SliderRow("Attack", st.attack[band], 0f, 500f, "ms") {
                if (linkAR) for (i in 0 until DspEngine.NUM_BANDS) { st.attack[i] = it; dsp.setAttack(i, it) }
                else { st.attack[band] = it; dsp.setAttack(band, it) }
            }
            SliderRow("Release", st.release[band], 0f, 3000f, "ms") {
                if (linkAR) for (i in 0 until DspEngine.NUM_BANDS) { st.release[i] = it; dsp.setRelease(i, it) }
                else { st.release[band] = it; dsp.setRelease(band, it) }
            }
            SliderRow("Ratio", st.ratio[band], 1f, 20f, ":1") {
                st.ratio[band] = it; dsp.setRatio(band, it)
            }
            SliderRow("Makeup", st.makeup[band], -12f, 24f, "dB") {
                st.makeup[band] = it; dsp.setBandPostGain(band, it)
            }
        }
    }
}

@Composable
private fun LimiterCard(dsp: DspEngine, st: UiState) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Output Limiter", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Switch(checked = st.limOn, onCheckedChange = {
                    st.limOn = it; dsp.setLimiterEnabled(it)
                })
            }
            SliderRow("Threshold", st.limThr, -60f, 0f, "dBFS") {
                st.limThr = it; dsp.setLimiterThreshold(it)
            }
            SliderRow("Attack", st.limAtk, 0f, 100f, "ms") {
                st.limAtk = it; dsp.setLimiterAttack(it)
            }
            SliderRow("Release", st.limRel, 0f, 1000f, "ms") {
                st.limRel = it; dsp.setLimiterRelease(it)
            }
            SliderRow("Ratio", st.limRatio, 1f, 50f, ":1") {
                st.limRatio = it; dsp.setLimiterRatio(it)
            }
            SliderRow("Post Gain", st.limPost, -12f, 12f, "dB") {
                st.limPost = it; dsp.setLimiterPostGain(it)
            }
        }
    }
}

@Composable
private fun GrMeter(grDb: Float, maxDb: Float = 24f) {
    val frac = (grDb / maxDb).coerceIn(0f, 1f)
    Column {
        Row {
            Text("GR", fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("-${"%.1f".format(grDb)} dB", fontSize = 11.sp)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).background(Color(0xFF222222))) {
            Box(Modifier.fillMaxWidth(frac).height(8.dp).background(
                when {
                    grDb > 12f -> Color(0xFFE53935)
                    grDb > 4f -> Color(0xFFFFB300)
                    else -> Color(0xFF43A047)
                }
            ))
        }
    }
}

@Composable
private fun SliderRow(
    label: String, value: Float, min: Float, max: Float, unit: String,
    onChange: (Float) -> Unit
) {
    Column {
        Row {
            Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp)
            Text("${"%.1f".format(value)} $unit", fontSize = 13.sp)
        }
        Slider(value = value, onValueChange = onChange, valueRange = min..max)
    }
}
