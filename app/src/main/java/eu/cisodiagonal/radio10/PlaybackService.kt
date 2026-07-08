package eu.cisodiagonal.radio10

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that streams the Radio 10 MP3 station and keeps a single,
 * stable audio session so the Optiforge [DspEngine] (5-band compressor/limiter)
 * stays attached across stream reconnects and station switches.
 *
 * Failover: [Station.urls] is an ordered list of mirror URLs. On a MediaPlayer
 * error the service advances to the next URL (wrapping around). After a full
 * failed cycle it backs off briefly, then keeps retrying — a dead node never
 * stops playback.
 *
 * The [dsp] and [meter] are owned here (they must outlive the Activity so audio
 * keeps processing in the background). The Activity binds, drives the DSP params
 * from its [UiState], and starts the [meter] once RECORD_AUDIO is granted.
 */
class PlaybackService : Service() {

    companion object {
        const val TAG = "PlaybackService"
        const val CHANNEL_ID = "dw_playback"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "eu.cisodiagonal.radio10.STOP"
        private const val FAILOVER_BACKOFF_MS = 5_000L
    }

    data class State(
        val playing: Boolean = false,
        val buffering: Boolean = false,
        val stationId: String = Stations.DANCE.id,
        val urlIndex: Int = 0,
        val urlCount: Int = Stations.DANCE.urls.size,
        val title: String = "",
        val error: String? = null,
    )

    inner class LocalBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    private val binder = LocalBinder()

    // Owned audio graph — survives Activity recreation.
    val dsp = DspEngine()
    val meter = BandMeter(dsp)

    private var sessionId = AudioManager.AUDIO_SESSION_ID_GENERATE
    private var player: MediaPlayer? = null
    private var station: Station = Stations.DANCE
    private var urlIndex = 0
    private var consecutiveFailures = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var nowPlayingJob: Job? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Bumped every time the DSP (re)attaches; the UI re-pushes params on change. */
    private val _sessionEpoch = MutableStateFlow(0)
    val sessionEpoch: StateFlow<Int> = _sessionEpoch.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        sessionId = am.generateAudioSessionId()
        // Attach the compressor/limiter once to the stable session. Every
        // MediaPlayer we build reuses this session id, so the effect persists.
        dsp.attach(sessionId)
        _sessionEpoch.value = _sessionEpoch.value + 1
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopPlayback()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    // ---- Public controls (called from the Activity) --------------------

    val audioSessionId: Int get() = sessionId

    fun play(st: Station = station) {
        station = st
        urlIndex = 0
        consecutiveFailures = 0
        startForegroundSafe()
        openCurrentUrl()
        startNowPlaying()
    }

    fun stopPlayback() {
        nowPlayingJob?.cancel()
        releasePlayer()
        _state.value = _state.value.copy(playing = false, buffering = false)
        updateNotification()
    }

    fun toggleStation(st: Station) {
        if (st.id == station.id && _state.value.playing) return
        play(st)
    }

    // ---- Internal playback ---------------------------------------------

    private fun openCurrentUrl() {
        releasePlayer()
        val url = station.urls[urlIndex]
        _state.value = _state.value.copy(
            buffering = true, playing = false, stationId = station.id,
            urlIndex = urlIndex, urlCount = station.urls.size, error = null,
        )
        updateNotification()
        Log.i(TAG, "opening [$urlIndex] $url")
        try {
            val mp = MediaPlayer()
            // Session id MUST be set before setDataSource so the effect binds.
            mp.audioSessionId = sessionId
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(url)
            mp.setOnPreparedListener {
                consecutiveFailures = 0
                it.start()
                _state.value = _state.value.copy(playing = true, buffering = false, error = null)
                updateNotification()
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra on [$urlIndex]")
                failover()
                true
            }
            mp.setOnCompletionListener {
                // A live stream shouldn't complete; treat as a drop → reconnect.
                Log.w(TAG, "stream completed unexpectedly → reconnect")
                failover()
            }
            mp.prepareAsync()
            player = mp
        } catch (e: Exception) {
            Log.e(TAG, "open failed on [$urlIndex]", e)
            failover()
        }
    }

    /** Advance to the next mirror; back off after a full failed cycle. */
    private fun failover() {
        releasePlayer()
        consecutiveFailures++
        urlIndex = (urlIndex + 1) % station.urls.size
        if (consecutiveFailures >= station.urls.size) {
            consecutiveFailures = 0
            _state.value = _state.value.copy(
                playing = false, buffering = true,
                error = "All mirrors unreachable — retrying…",
            )
            updateNotification()
            scope.launch {
                delay(FAILOVER_BACKOFF_MS)
                if (_state.value.buffering || _state.value.playing) openCurrentUrl()
            }
        } else {
            openCurrentUrl()
        }
    }

    private fun releasePlayer() {
        player?.let {
            try { it.reset(); it.release() } catch (_: Exception) {}
        }
        player = null
    }

    // ---- Now playing ---------------------------------------------------
    // Radio 10 (StreamTheWorld) has no now-playing feed wired here; the
    // notification/title just shows the station name.

    private fun startNowPlaying() {
        nowPlayingJob?.cancel()
        _state.value = _state.value.copy(title = station.name)
    }

    // ---- Foreground notification ---------------------------------------

    private fun startForegroundSafe() {
        startForeground(NOTIF_ID, buildNotification())
    }

    private fun updateNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val s = _state.value
        val text = when {
            s.error != null -> s.error
            s.buffering -> "Connecting… (mirror ${s.urlIndex + 1}/${s.urlCount})"
            s.playing -> s.title.ifEmpty { station.name }
            else -> "Stopped"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(station.name)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(s.playing || s.buffering)
            .addAction(
                Notification.Action.Builder(null, "Stop", stop).build()
            )
            .build()
    }

    private fun createChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
    }

    override fun onDestroy() {
        scope.cancel()
        releasePlayer()
        meter.stop()
        dsp.release()
        super.onDestroy()
    }
}
