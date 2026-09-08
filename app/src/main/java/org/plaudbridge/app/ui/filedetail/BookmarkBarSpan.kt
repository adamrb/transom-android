package org.plaudbridge.app.ui.filedetail

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.style.LeadingMarginSpan

/**
 * A thin accent bar down the left edge of a transcript paragraph that holds a bookmark (a recorder
 * button press), so the eye finds it while scrolling. Works like the platform's QuoteSpan (whose
 * width/gap constructor needs API 28) with the bar drawn as a rounded strip; the paragraph text
 * is indented by [barWidth] + [gapWidth] so it stays aligned across its lines.
 */
class BookmarkBarSpan(
    private val color: Int,
    private val barWidth: Int,
    private val gapWidth: Int
) : LeadingMarginSpan {

    override fun getLeadingMargin(first: Boolean): Int = barWidth + gapWidth

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout
    ) {
        val style = p.style
        val paintColor = p.color
        p.style = Paint.Style.FILL
        p.color = color
        val left = if (dir > 0) x.toFloat() else (x - barWidth).toFloat()
        val radius = barWidth / 2f
        c.drawRoundRect(left, top.toFloat(), left + barWidth, bottom.toFloat(), radius, radius, p)
        p.style = style
        p.color = paintColor
    }
}
