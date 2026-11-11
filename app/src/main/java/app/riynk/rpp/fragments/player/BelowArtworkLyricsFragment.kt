package app.riynk.rpp.fragments.player

/**
 * RP Recommended hosts the existing synchronized lyric preview below the artwork.
 * The lyric repository and playback synchronization remain shared.
 */
class BelowArtworkLyricsFragment : CoverLyricsFragment() {
    override val isBelowArtworkPreview: Boolean = true
}
