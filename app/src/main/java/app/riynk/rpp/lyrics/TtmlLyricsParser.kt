package app.riynk.rpp.lyrics

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import kotlin.math.roundToLong

/**
 * A source-faithful representation of one TTML document.
 *
 * A timed span remains one segment. We deliberately do not divide a phrase into
 * guessed word timings: karaoke highlighting is only as granular as the source.
 */
data class TtmlLyrics(
    val lines: List<TtmlLine>,
    val timingMode: String = "Line"
) {
    val isValid: Boolean get() = lines.any { it.text.isNotBlank() && it.hasTiming }
}

data class TtmlLine(
    val startMs: Long,
    val endMs: Long?,
    val text: String,
    val agent: String?,
    val alignment: TtmlAlignment,
    val segments: List<TtmlSegment>,
    val hasTiming: Boolean
)

data class TtmlSegment(
    val text: String,
    val startMs: Long?,
    val endMs: Long?,
    val isBackground: Boolean
)

enum class TtmlAlignment { LEFT, RIGHT }

/**
 * Namespace-aware parser for the Apple/TimeLyr TTML dialect used by Local Player.
 * Supports clock times, offset times, inherited paragraph timing, agent alignment,
 * and the TTML metadata background-vocal role.
 */
object TtmlLyricsParser {
    private const val NS_TTM = "http://www.w3.org/ns/ttml#metadata"
    private const val NS_ITUNES = "http://music.apple.com/lyric-ttml-internal"

    fun parse(content: String): TtmlLyrics? {
        if (content.isBlank()) return null
        return try {
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setInput(StringReader(content))
            }
            var timingMode = "Line"
            var frameRate = 30.0
            var tickRate = 1.0
            val lines = mutableListOf<TtmlLine>()

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "tt" -> {
                            timingMode = parser.getAttributeValue(NS_ITUNES, "timing") ?: "Line"
                            frameRate = parser.getAttributeValue(null, "frameRate")
                                ?.toDoubleOrNull() ?: 30.0
                            tickRate = parser.getAttributeValue(null, "tickRate")
                                ?.toDoubleOrNull() ?: frameRate
                        }
                        "p" -> parseParagraph(parser, frameRate, tickRate)?.let(lines::add)
                    }
                }
                event = parser.next()
            }
            val result = TtmlLyrics(lines.sortedBy { it.startMs }, timingMode)
            result.takeIf { it.isValid }
        } catch (_: Exception) {
            null
        }
    }

    private data class ParsedContent(
        val text: StringBuilder = StringBuilder(),
        val segments: MutableList<TtmlSegment> = mutableListOf()
    )

    private fun parseParagraph(
        parser: XmlPullParser,
        frameRate: Double,
        tickRate: Double
    ): TtmlLine? {
        val beginRaw = parser.getAttributeValue(null, "begin")
        val endRaw = parser.getAttributeValue(null, "end")
        val durRaw = parser.getAttributeValue(null, "dur")
        val lineStart = beginRaw?.let { parseTime(it, frameRate, tickRate, null) } ?: 0L
        val explicitEnd = endRaw?.let { parseTime(it, frameRate, tickRate, lineStart) }
        val durationEnd = durRaw?.let { lineStart + (parseTime(it, frameRate, tickRate, null) ?: 0L) }
        val lineEnd = explicitEnd ?: durationEnd
        val agent = parser.getAttributeValue(NS_TTM, "agent")
            ?: parser.getAttributeValue(null, "agent")
        val alignment = if (agent?.substringAfterLast("v")?.toIntOrNull() == 2) {
            TtmlAlignment.RIGHT
        } else {
            TtmlAlignment.LEFT
        }

        val parsed = parseChildren(
            parser, "p", if (beginRaw != null) lineStart else null,
            lineEnd, false, frameRate, tickRate
        )
        val text = parsed.text.toString()
        if (text.isBlank()) return null

        val segments = parsed.segments.filter { it.text.isNotEmpty() }
        val inferredStart = segments.mapNotNull { it.startMs }.minOrNull()
        val inferredEnd = segments.mapNotNull { it.endMs }.maxOrNull()
        return TtmlLine(
            startMs = inferredStart ?: lineStart,
            endMs = lineEnd ?: inferredEnd,
            text = text,
            agent = agent,
            alignment = alignment,
            segments = if (segments.isEmpty()) {
                listOf(TtmlSegment(text, lineStart, lineEnd, false))
            } else segments,
            hasTiming = beginRaw != null || endRaw != null || durRaw != null ||
                segments.any { it.startMs != null || it.endMs != null }
        )
    }

    /** Called while the parser is positioned on the opening tag. */
    private fun parseChildren(
        parser: XmlPullParser,
        closingTag: String,
        parentStart: Long?,
        parentEnd: Long?,
        inheritedBackground: Boolean,
        frameRate: Double,
        tickRate: Double
    ): ParsedContent {
        val result = ParsedContent()
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name
                    val begin = parser.getAttributeValue(null, "begin")
                        ?.let { parseTime(it, frameRate, tickRate, parentStart) }
                    val end = parser.getAttributeValue(null, "end")
                        ?.let { parseTime(it, frameRate, tickRate, parentStart) }
                    val dur = parser.getAttributeValue(null, "dur")
                        ?.let { parseTime(it, frameRate, tickRate, null) }
                    val start = begin ?: parentStart
                    val explicitFinish = end ?: dur?.let { (start ?: 0L) + it }
                    // A child with its own begin but no end is closed by the next
                    // timed sibling, not by the paragraph's full end.
                    val nestedEnd = explicitFinish ?: if (begin == null) parentEnd else null
                    val background = inheritedBackground ||
                        (parser.getAttributeValue(NS_TTM, "role") == "x-bg")

                    val nested = parseChildren(
                        parser, tag, start, nestedEnd, background, frameRate, tickRate
                    )
                    result.text.append(nested.text)
                    if (tag == "span") {
                        if (nested.segments.isEmpty()) {
                            val value = nested.text.toString()
                            if (value.isNotEmpty()) {
                                result.segments.add(TtmlSegment(value, start, finish, background))
                            }
                        } else {
                            result.segments.addAll(nested.segments)
                        }
                    } else {
                        result.segments.addAll(nested.segments)
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    val value = parser.text ?: ""
                    result.text.append(value)
                    if (value.isNotEmpty()) {
                        result.segments.add(
                            TtmlSegment(value, parentStart, parentEnd, inheritedBackground)
                        )
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == closingTag) return result
                XmlPullParser.END_DOCUMENT -> return result
            }
        }
    }

    /**
     * TTML clock values (00:12.345 / 00:01:12.345), offset values (12.3s,
     * 120ms, 2m, 1h), and frame/tick values. Offset values inside timed spans
     * are relative to their parent; clock values are timeline positions.
     */
    private fun parseTime(
        raw: String,
        frameRate: Double,
        tickRate: Double,
        parentStart: Long?
    ): Long? {
        val value = raw.trim()
        if (value.isEmpty()) return null

        val clock = Regex("^(?:(\\d+):)?(\\d{1,2}):(\\d{2})(?:\\.(\\d+))?$")
            .matchEntire(value)
        if (clock != null) {
            val hours = clock.groupValues[1].takeIf { it.isNotEmpty() }?.toLongOrNull() ?: 0L
            val minutes = clock.groupValues[2].toLongOrNull() ?: return null
            val seconds = clock.groupValues[3].toLongOrNull() ?: return null
            val fraction = clock.groupValues[4]
            val millis = if (fraction.isEmpty()) 0L else
                (("0.$fraction".toDoubleOrNull() ?: 0.0) * 1000.0).roundToLong()
            return ((hours * 3600 + minutes * 60 + seconds) * 1000L + millis)
        }

        val offset = Regex("^(-?\\d+(?:\\.\\d+)?)(h|m|s|ms|f|t)?$")
            .matchEntire(value) ?: return null
        val amount = offset.groupValues[1].toDoubleOrNull() ?: return null
        val unit = offset.groupValues[2]
        val millis = when (unit) {
            "h" -> amount * 3_600_000.0
            "m" -> amount * 60_000.0
            "s", "" -> amount * 1000.0
            "ms" -> amount
            "f" -> amount / frameRate * 1000.0
            "t" -> amount / tickRate * 1000.0
            else -> return null
        }.roundToLong()
        return if (parentStart != null) parentStart + millis else millis
    }
}
