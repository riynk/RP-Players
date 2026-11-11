package app.riynk.rpp.lyrics

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * TTML renderer. Keeps the existing RP PLAYER View/API while following
 * Local Player's word-fill, secondary-vocal, line-scale and scroll behaviour.
 */
class TtmlLyricsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    fun interface OnSeekListener {
        fun onSeek(timeMs: Long)
    }

    private data class RenderedLine(
        val main: StaticLayout,
        val background: StaticLayout?,
        val height: Int
    )

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var lyrics: TtmlLyrics? = null
    private var currentLine = -1
    private var currentTime = 0L
    private var playbackPositionMs = 0L
    private var playbackRate = 1f
    private var currentTextSize = 30f
    private var normalTextSize = 30f
    private var backgroundTextSize = 24f
    private var currentColor = Color.WHITE
    private var normalColor = 0x66FFFFFF
    private var backgroundColor = 0x66FFFFFF
    private var lineGap = 12f
    private var scrollOffset = 0f
    private var targetOffset = 0f
    private var animator: ValueAnimator? = null
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var manualScrollUntil = 0L
    private var seekListener: OnSeekListener? = null
    private val density = resources.displayMetrics.density

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        paint.typeface = Typeface.DEFAULT_BOLD
    }

    fun setLyrics(value: TtmlLyrics?) {
        animator?.cancel()
        lyrics = value
        currentLine = -1
        currentTime = 0L
        playbackPositionMs = 0L
        scrollOffset = 0f
        targetOffset = 0f
        invalidate()
    }

    /** Values are pixels, matching the existing LyricsFragment API. */
    fun setTextSizes(current: Float, normal: Float) {
        currentTextSize = current
        normalTextSize = normal
        invalidate()
    }

    fun setCurrentColor(color: Int) {
        currentColor = color
        invalidate()
    }

    fun setNormalColor(color: Int) {
        normalColor = color
        invalidate()
    }

    fun setBackgroundVocalColor(color: Int) {
        backgroundColor = color
        invalidate()
    }

    fun setOnSeekListener(listener: OnSeekListener?) {
        seekListener = listener
    }

    fun setPlaybackRate(rate: Float) {
        playbackRate = rate.coerceIn(0.5f, 2.0f)
        updateTime(playbackPositionMs)
    }

    fun updateTime(timeMs: Long) {
        playbackPositionMs = timeMs.coerceAtLeast(0L)
        currentTime = (playbackPositionMs * playbackRate).toLong().coerceAtLeast(0L)
        val items = lyrics?.lines ?: return
        if (items.isEmpty()) return
        val nextLine = findLine(currentTime, items)
        if (nextLine != currentLine) {
            currentLine = nextLine
            if (System.currentTimeMillis() >= manualScrollUntil) animateToCurrentLine()
        }
        invalidate()
    }

    private fun findLine(time: Long, items: List<TtmlLine>): Int {
        var low = 0
        var high = items.lastIndex
        // No lyric is active before the first source timestamp. Returning 0 here
        // made the first line highlight early whenever a song had an intro.
        if (items.isEmpty() || time < items.first().startMs) return -1

        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (items[mid].startMs <= time) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    // Match Local Player's active-index rule while keeping simultaneous duet rows active.
    private fun isLineActive(index: Int, line: TtmlLine): Boolean {
        val activeStart = lyrics?.lines?.getOrNull(currentLine)?.startMs ?: return false
        return index == currentLine || line.startMs == activeStart
    }

    private fun hasWordTiming(line: TtmlLine): Boolean =
        line.segments.any { !it.isBackground && it.isTimedSpan }

    private fun makeSegmentLayout(
        segments: List<TtmlSegment>,
        fallbackText: String,
        textPaint: TextPaint,
        activeLine: Boolean,
        activeTint: Int,
        line: TtmlLine,
        wordTiming: Boolean
    ): StaticLayout {
        val builder = SpannableStringBuilder()
        if (segments.isEmpty()) {
            builder.append(fallbackText)
            if (activeLine && builder.isNotEmpty()) {
                builder.setSpan(
                    ForegroundColorSpan(activeTint),
                    0, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        } else {
            segments.forEachIndexed { index, segment ->
                val start = builder.length
                builder.append(segment.text)
                val end = builder.length
                if (end <= start) return@forEachIndexed

                val nextTimedStart = segments.drop(index + 1).firstNotNullOfOrNull {
                    it.startMs?.takeIf { next -> segment.startMs == null || next > segment.startMs }
                }
                val segmentEnd = segment.endMs ?: nextTimedStart ?: line.endMs
                val progress = when {
                    !activeLine -> 0f
                    !wordTiming -> 1f
                    segment.startMs == null -> 0f
                    currentTime < segment.startMs -> 0f
                    segmentEnd != null && segmentEnd > segment.startMs ->
                        ((currentTime - segment.startMs).toFloat() /
                            (segmentEnd - segment.startMs).toFloat()).coerceIn(0f, 1f)
                    else -> 1f
                }

                val startX = textPaint.measureText(builder, 0, start)
                val segmentWidth = textPaint.measureText(segment.text).coerceAtLeast(1f)
                builder.setSpan(
                    ProgressiveFillCharacterSpan(
                        baseColor = textPaint.color,
                        activeColor = activeTint,
                        progress = progress,
                        startX = startX,
                        endX = startX + segmentWidth
                    ),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        val contentWidth = max(1, width - paddingLeft - paddingRight)
        val multipleVoices = (lyrics?.lines?.mapNotNull { it.agent }?.distinct()?.size ?: 0) > 1
        val layoutWidth = (contentWidth * if (multipleVoices) 0.7f else 1f).toInt().coerceAtLeast(1)
        return StaticLayout.Builder.obtain(builder, 0, builder.length, textPaint, layoutWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(if (textPaint.textSize <= backgroundTextSize * density + 1f) 8f * density else 10f * density, 1f)
            .build()
    }

    private fun buildLayout(line: TtmlLine, index: Int): RenderedLine {
        val active = isLineActive(index, line)
        val wordTiming = hasWordTiming(line)
        val mainSegments = line.segments.filterNot { it.isBackground }
        val backgroundSegments = line.segments.filter { it.isBackground }

        val mainPaint = TextPaint(paint).apply {
            textSize = if (active) currentTextSize else normalTextSize
            typeface = Typeface.DEFAULT_BOLD
            color = normalColor
            shader = null
        }
        val mainFallback = if (mainSegments.isEmpty() && backgroundSegments.isEmpty()) line.text else ""
        val main = makeSegmentLayout(
            mainSegments, mainFallback, mainPaint, active, currentColor, line, wordTiming
        )

        val background = if (active && backgroundSegments.isNotEmpty()) {
            val secondaryPaint = TextPaint(paint).apply {
                textSize = backgroundTextSize * density
                typeface = Typeface.DEFAULT_BOLD
                color = backgroundColor
                shader = null
            }
            makeSegmentLayout(
                backgroundSegments, "", secondaryPaint, true,
                (currentColor and 0x00FFFFFF) or 0x99000000.toInt(),
                line, wordTiming
            )
        } else null

        val gap = if (background != null) (2f * density).roundToInt() else 0
        return RenderedLine(main, background, main.height + gap + (background?.height ?: 0))
    }

    private fun lineTopPositions(items: List<TtmlLine>): FloatArray {
        val tops = FloatArray(items.size)
        var y = 0f
        items.forEachIndexed { index, line ->
            tops[index] = y
            y += buildLayout(line, index).height + lineGap * density
        }
        return tops
    }

    private fun animateToCurrentLine() {
        val items = lyrics?.lines ?: return
        if (currentLine !in items.indices || height == 0) return
        val tops = lineTopPositions(items)
        // Local Player keeps the active lyric around the upper third of the viewport.
        val desired = tops[currentLine] + buildLayout(items[currentLine], currentLine).height / 2f -
            (height - paddingTop - paddingBottom) / 3f
        targetOffset = desired.coerceAtLeast(0f)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(scrollOffset, targetOffset).apply {
            duration = 320L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                scrollOffset = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val items = lyrics?.lines ?: return
        if (items.isEmpty()) return
        val tops = lineTopPositions(items)
        val contentWidth = max(1, width - paddingLeft - paddingRight)
        val multipleVoices = (items.mapNotNull { it.agent }.distinct().size) > 1
        val layoutWidth = (contentWidth * if (multipleVoices) 0.7f else 1f).toInt().coerceAtLeast(1)

        items.forEachIndexed { index, line ->
            val rendered = buildLayout(line, index)
            val y = paddingTop + tops[index] - scrollOffset
            if (y + rendered.height < 0 || y > height) return@forEachIndexed

            val active = isLineActive(index, line)
            val distance = abs(index - currentLine)
            val alpha = when (distance) {
                0 -> 1f
                1 -> 0.75f
                2 -> 0.5f
                else -> 0.28f
            }
            val left = if (line.alignment == TtmlAlignment.RIGHT) {
                paddingLeft + contentWidth - layoutWidth
            } else {
                paddingLeft
            }
            val pivotX = if (line.alignment == TtmlAlignment.RIGHT) layoutWidth.toFloat() else 0f

            canvas.save()
            canvas.saveLayerAlpha(
                left.toFloat(), y, (left + layoutWidth).toFloat(), y + rendered.height,
                (alpha * 255).roundToInt()
            )
            if (active) {
                canvas.translate(left + pivotX, y + rendered.height / 2f)
                canvas.scale(1.05f, 1.05f)
                canvas.translate(-pivotX, -rendered.height / 2f)
            } else {
                canvas.translate(left.toFloat(), y)
            }
            rendered.main.draw(canvas)
            rendered.background?.let {
                canvas.translate(0f, rendered.main.height + 2f * density)
                it.draw(canvas)
            }
            canvas.restore()
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                downY = event.y
                lastY = event.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = lastY - event.y
                if (abs(event.y - downY) > 8f * density) dragging = true
                if (dragging) {
                    scrollOffset = (scrollOffset + delta).coerceAtLeast(0f)
                    manualScrollUntil = System.currentTimeMillis() + 4500L
                    invalidate()
                }
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    val items = lyrics?.lines.orEmpty()
                    if (items.isNotEmpty()) {
                        val targetY = event.y + scrollOffset - paddingTop
                        val tops = lineTopPositions(items)
                        val index = tops.indexOfLast { it <= targetY }.coerceIn(0, items.lastIndex)
                        seekListener?.onSeek(items[index].startMs)
                    }
                } else {
                    manualScrollUntil = System.currentTimeMillis() + 4500L
                    postDelayed({
                        if (System.currentTimeMillis() >= manualScrollUntil) animateToCurrentLine()
                    }, 4550L)
                }
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/** Progressive fill is applied as a character style so adjacent TTML spans
 * keep Android's normal glyph shaping and kerning. */
private class ProgressiveFillCharacterSpan(
    private val baseColor: Int,
    private val activeColor: Int,
    private val progress: Float,
    private val startX: Float,
    private val endX: Float
) : CharacterStyle() {
    override fun updateDrawState(textPaint: TextPaint) {
        val amount = progress.coerceIn(0f, 1f)
        when {
            amount <= 0f -> {
                textPaint.shader = null
                textPaint.color = baseColor
            }
            amount >= 1f -> {
                textPaint.shader = null
                textPaint.color = activeColor
            }
            else -> {
                textPaint.color = Color.WHITE
                textPaint.shader = LinearGradient(
                    startX, 0f, endX.coerceAtLeast(startX + 1f), 0f,
                    intArrayOf(activeColor, activeColor, baseColor),
                    floatArrayOf(0f, amount, amount),
                    Shader.TileMode.CLAMP
                )
            }
        }
    }
}
