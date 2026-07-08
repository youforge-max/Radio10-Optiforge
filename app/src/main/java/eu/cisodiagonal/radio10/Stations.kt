package eu.cisodiagonal.radio10

/**
 * Radio 10 (NL) MP3 stream with an ordered [urls] failover list — the player
 * advances to the next URL on stream error and wraps around, so a dead node
 * never stops playback. 128 kbps MP3.
 *
 * Served by Talpa via StreamTheWorld. The primary URL is the livestream-redirect
 * endpoint, which load-balances to a live edge node on every connect; the rest
 * are concrete edge nodes from the .pls as backup.
 */
data class Station(
    val id: String,
    val name: String,
    val urls: List<String>,
    /** now-playing mount, or null if unknown (Radio 10 has none here). */
    val nowPlayingMount: String?,
)

object Stations {

    val RADIO10 = Station(
        id = "radio10",
        name = "Radio 10",
        urls = listOf(
            "https://playerservices.streamtheworld.com/api/livestream-redirect/RADIO10.mp3",
            "https://20103.live.streamtheworld.com/RADIO10.mp3",
            "https://22343.live.streamtheworld.com/RADIO10.mp3",
            "https://25683.live.streamtheworld.com/RADIO10.mp3",
            "https://19983.live.streamtheworld.com/RADIO10.mp3",
        ),
        nowPlayingMount = null,
    )

    /** Default station alias used by the service/UI. */
    val DANCE = RADIO10
    val ALL = listOf(RADIO10)
}
