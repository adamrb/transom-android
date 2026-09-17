package io.github.adamrb.transom.ui.filedetail

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as MaterialR
import io.github.adamrb.transom.ui.common.themeColor
import kotlin.math.roundToInt

/**
 * A fast scroller for the transcript list, laid over its right edge: a thumb that appears while
 * the list scrolls and fades out after a moment, and that can be dragged to fly through a long
 * transcript with a bubble showing the time ("0:47:12") of the paragraph under it. Only shown
 * when the transcript is long enough to need it, see [MIN_PARAGRAPHS].
 *
 * Positions are mapped by paragraph index rather than pixel height (rows differ in height), so
 * dragging to the middle lands on the middle paragraph, which is what a reader expects.
 */
class TranscriptFastScroller @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** What the scroller needs from the page. */
    interface Host {
        val paragraphCount: Int
        /** The time label for a paragraph, or null when the document carries no times. */
        fun timeLabelAt(paragraphIndex: Int): String?
        /** Put the paragraph at the top of the viewport. */
        fun scrollToParagraph(paragraphIndex: Int)
        /** The reader took the wheel: playback should stop following the text. */
        fun onUserScrollGesture()
    }

    private var recycler: RecyclerView? = null
    private var host: Host? = null
    private val density = resources.displayMetrics.density
    private val thumbWidth = 6 * density
    private val thumbHeight = 48 * density
    private val thumbMarginEnd = 6 * density
    private val bubbleHeight = 36 * density
    private val bubblePadding = 14 * density
    private val bubbleGap = 12 * density

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(MaterialR.attr.colorOnSurfaceVariant) }
    private val thumbActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(MaterialR.attr.colorPrimary) }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(MaterialR.attr.colorPrimary) }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(MaterialR.attr.colorOnPrimary)
        textSize = 14 * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val rect = RectF()

    private var thumbAlpha = 0f
    private var fadeAnimator: ValueAnimator? = null
    private var dragging = false
    private var dragFraction = 0f
    private var dragIndex = -1

    private val hideRunnable = Runnable { fadeThumb(0f) }

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
            if (dy != 0 && !dragging) showThumb()
        }
    }

    fun attach(recyclerView: RecyclerView, host: Host) {
        recycler?.removeOnScrollListener(scrollListener)
        recycler = recyclerView
        this.host = host
        recyclerView.addOnScrollListener(scrollListener)
    }

    /** Call when the paragraphs changed; hides the scroller for a short transcript. */
    fun refresh() {
        if (!isUsable) {
            removeCallbacks(hideRunnable)
            fadeAnimator?.cancel()
            thumbAlpha = 0f
            dragging = false
        }
        invalidate()
    }

    private val isUsable: Boolean get() = (host?.paragraphCount ?: 0) >= MIN_PARAGRAPHS

    private fun showThumb() {
        if (!isUsable) return
        fadeThumb(1f)
        removeCallbacks(hideRunnable)
        postDelayed(hideRunnable, HIDE_DELAY_MS)
    }

    private fun fadeThumb(target: Float) {
        fadeAnimator?.cancel()
        if (thumbAlpha == target) return
        if (!animationsEnabled()) {
            thumbAlpha = target
            invalidate()
            return
        }
        fadeAnimator = ValueAnimator.ofFloat(thumbAlpha, target).apply {
            duration = 150
            addUpdateListener {
                thumbAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun animationsEnabled(): Boolean =
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    /** 0..1 along the track, from the list's own scroll estimate (or the drag while dragging). */
    private fun currentFraction(): Float {
        if (dragging) return dragFraction
        val rv = recycler ?: return 0f
        val range = rv.computeVerticalScrollRange() - rv.computeVerticalScrollExtent()
        if (range <= 0) return 0f
        return (rv.computeVerticalScrollOffset().toFloat() / range).coerceIn(0f, 1f)
    }

    private val trackTop: Float get() = paddingTop + bubbleHeight / 2
    private val trackHeight: Float get() = (height - paddingTop - paddingBottom - bubbleHeight / 2 - thumbHeight).coerceAtLeast(1f)

    private fun thumbTop(fraction: Float): Float = trackTop + fraction * trackHeight

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isUsable || (thumbAlpha <= 0f && !dragging)) return false
        val h = host ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val top = thumbTop(currentFraction())
                val grab = event.y >= top - thumbHeight / 2 && event.y <= top + thumbHeight * 1.5f &&
                    event.x >= width - thumbWidth - thumbMarginEnd - 40 * density
                if (!grab) return false
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                removeCallbacks(hideRunnable)
                fadeAnimator?.cancel()
                thumbAlpha = 1f
                h.onUserScrollGesture()
                updateDrag(event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                updateDrag(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                dragIndex = -1
                postDelayed(hideRunnable, HIDE_DELAY_MS)
                invalidate()
                return true
            }
        }
        return false
    }

    private fun updateDrag(y: Float) {
        val h = host ?: return
        dragFraction = ((y - trackTop - thumbHeight / 2) / trackHeight).coerceIn(0f, 1f)
        val index = indexForFraction(dragFraction, h.paragraphCount)
        if (index >= 0 && index != dragIndex) {
            dragIndex = index
            h.scrollToParagraph(index)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (thumbAlpha <= 0f || !isUsable) return
        val fraction = currentFraction()
        val top = thumbTop(fraction)
        val right = width - paddingEnd - thumbMarginEnd
        val paint = if (dragging) thumbActivePaint else thumbPaint
        paint.alpha = (255 * thumbAlpha).roundToInt()
        rect.set(right - thumbWidth, top, right, top + thumbHeight)
        canvas.drawRoundRect(rect, thumbWidth / 2, thumbWidth / 2, paint)
        if (!dragging) return
        val label = host?.timeLabelAt(dragIndex) ?: return
        val textWidth = bubbleTextPaint.measureText(label)
        val bubbleRight = right - thumbWidth - bubbleGap
        val bubbleLeft = bubbleRight - textWidth - 2 * bubblePadding
        val centerY = top + thumbHeight / 2
        rect.set(bubbleLeft, centerY - bubbleHeight / 2, bubbleRight, centerY + bubbleHeight / 2)
        canvas.drawRoundRect(rect, bubbleHeight / 2, bubbleHeight / 2, bubblePaint)
        val baseline = centerY - (bubbleTextPaint.descent() + bubbleTextPaint.ascent()) / 2
        canvas.drawText(label, bubbleLeft + bubblePadding, baseline, bubbleTextPaint)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideRunnable)
        fadeAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    /** Whether the thumb is currently being dragged (for tests and the follow logic). */
    val isDragging: Boolean get() = dragging

    companion object {
        /** Shorter transcripts scroll fine by hand; the thumb would only get in the way of the text. */
        const val MIN_PARAGRAPHS = 30
        private const val HIDE_DELAY_MS = 1_500L

        /** The paragraph a fraction of the track maps to (also used by tests). */
        fun indexForFraction(fraction: Float, paragraphCount: Int): Int =
            if (paragraphCount <= 0) -1 else (fraction.coerceIn(0f, 1f) * (paragraphCount - 1)).roundToInt()
    }
}
