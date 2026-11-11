package app.riynk.rpp.lyrics

import app.riynk.rpp.model.Song
import app.riynk.rpp.util.LyricUtil
import app.riynk.rpp.model.lyrics.AbsSynchronizedLyrics

/**
 * Selects one authoritative synchronized-lyrics source for every player surface.
 *
 * Call from a background dispatcher: sidecar and embedded-tag reads are blocking.
 * TTML and LRC remain separate source formats; this class never converts or merges them.
 */
sealed class LyricsDocument {
    data class Ttml(val value: TtmlLyrics) : LyricsDocument()
    data class Lrc(val content: String) : LyricsDocument()
    data object None : LyricsDocument()
}

object LyricsRepository {
    fun load(song: Song): LyricsDocument {
        val sidecarTtml = LyricUtil.getSyncedLyricsTtmlFile(song)
            ?.let { runCatching { it.readText() }.getOrNull() }
        val parsedSidecar = sidecarTtml?.let(TtmlLyricsParser::parse)
        if (parsedSidecar?.isValid == true) return LyricsDocument.Ttml(parsedSidecar)

        val embeddedTtml = LyricUtil.getEmbeddedTtmlLyrics(song.data)
            ?.let(TtmlLyricsParser::parse)
        if (embeddedTtml?.isValid == true) return LyricsDocument.Ttml(embeddedTtml)

        val sidecarLrc = LyricUtil.getSyncedLyricsFile(song)
            ?.let { runCatching { it.readText() }.getOrNull() }
            ?.takeIf { it.isNotBlank() && AbsSynchronizedLyrics.isSynchronized(it) }
        if (sidecarLrc != null) return LyricsDocument.Lrc(sidecarLrc)

        val embeddedLrc = LyricUtil.getEmbeddedSyncedLyrics(song.data)
            ?.takeIf { it.isNotBlank() && AbsSynchronizedLyrics.isSynchronized(it) }
        if (embeddedLrc != null) return LyricsDocument.Lrc(embeddedLrc)

        return LyricsDocument.None
    }
}
