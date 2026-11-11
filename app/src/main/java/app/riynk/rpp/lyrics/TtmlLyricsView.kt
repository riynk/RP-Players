package app.riynk.rpp.lyrics

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.max

/**
 * TTML renderer with timed segment highlighting, agent-based left/right alignment,
 * and smaller background-vocal spans. It keeps the existing LrcView untouched.
 */
class TtmlLyricsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    fun interface OnSeekListener {
        fun onSeek(timeMs: Long)
    }

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var lyrics: TtmlLyrics? = null
    private var currentLine = -1
    private var currentTime = 0L
    private var currentTextSize = 38f
    private var normalTextSize = 30f
    private var currentColor = Color.WHITE
    private var normalColor = 0x99FFFFFF.toInt()
    private var backgroundColor = 0x80FFFFFF.toInt()
    private var lineGap = 30f
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
        paint.textSize = currentTextSize
    }

    fun setLyrics(value: TtmlLyrics?) {
        animator?.cancel()
        lyrics = value
        currentLine = -1
        currentTime = 0L
        scrollOffset = 0f
        targetOffset = 0f
        invalidate()
    }

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

    fun updateTime(timeMs: Long) {
        currentTime = timeMs.coerceAtLeast(0L)
        val items = lyrics?.lines ?: return
        if (items.isEmpty()) return
        val nextLine = findLine(currentTime, items)
        if (nextLine != currentLine) {
            currentLine = nextLine
            if (System.currentTimeMillis() >= manualScrollUntil) {
                animateToCurrentLine()
            }
        }
        invalidate()
    }

    private fun findLine(time: Long, items: List<TtmlLine>): Int {
        var low = 0
        var high = items.lastIndex
        var result = 0
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

    private fun isLineActive(index: Int, line: TtmlLine): Boolean {
        val items = lyrics?.lines ?: return index == currentLine
        val nextStart = items.drop(index + 1)
            .firstOrNull { it.startMs > line.startMs }?.startMs
        val effectiveEnd = line.endMs ?: nextStart
        return currentTime >= line.startMs &&
            (effectiveEnd == null || currentTime < effectiveEnd)
    }

    private fun buildLayout(line: TtmlLine, index: Int): StaticLayout {
        val activeLine = isLineActive(index, line)
        val builder = SpannableStringBuilder()
        paint.color = normalColor
        if (line.segments.isEmpty()) {
            builder.append(line.text)
            if (activeLine && builder.isNotEmpty()) {
                builder.setSpan(ForegroundColorSpan(currentColor), 0, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else {
            line.segments.forEachIndexed { segmentIndex, segment ->
                val start = builder.length
                builder.append(segment.text)
                val end = builder.length
                if (end > start) {
                    val nextStart = line.segments.drop(segmentIndex + 1)
                        .firstNotNullOfOrNull { it.startMs }
                    val effectiveEnd = segment.endMs ?: nextStart ?: line.endMs
                    val segmentActive = activeLine &&
                        segment.startMs != null &&
                        currentTime >= segment.startMs &&
                        (effectiveEnd == null || currentTime < effectiveEnd)
                    builder.setSpan(
                        ForegroundColorSpan(
                            if (segmentActive) currentColor
                            else if (segment.isBackground) backgroundColor
                            else normalColor
                        ),
                        start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    if (segment.isBackground) {
                        builder.setSpan(
                            RelativeSizeSpan(0.68f),
                            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
            }
        }
        paint.textSize = if (activeLine) currentTextSize else normalTextSize
        val alignment = if (line.alignment == TtmlAlignment.RIGHT) {
            Layout.Alignment.ALIGN_OPPOSITE
        } else {
            Layout.Alignment.ALIGN_NORMAL
        }
        return StaticLayout.Builder.obtain(builder, 0, builder.length, paint, max(1, width - paddingLeft - paddingRight))
            .setAlignment(alignment)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.12f)
            .build()
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
        val layout = buildLayout(items[currentLine], currentLine)
        val desired = tops[currentLine] + layout.height / 2f - height / 2f
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
        items.forEachIndexed { index, line ->
            val layout = buildLayout(line, index)
            val y = paddingTop + tops[index] - scrollOffset
            if (y + layout.height < 0 || y > height) return@forEachIndexed
            canvas.save()
            canvas.translate(paddingLeft.toFloat(), y)
            layout.draw(canvas)
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
                if (kotlin.math.abs(event.y - downY) > 8f * density) dragging = true
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
                        if (System.currentTimeMillis() >= manualScrollUntil) {
                            animateToCurrentLine()
                        }
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
