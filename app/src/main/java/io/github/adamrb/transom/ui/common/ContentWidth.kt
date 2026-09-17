package io.github.adamrb.transom.ui.common

import android.view.View
import io.github.adamrb.transom.R

/**
 * Keeps a page's content a readable column on wide screens (a tablet, the inner display of a
 * foldable). Phones are untouched: `R.dimen.pb_content_max_width` is 0 there. On a wide screen the
 * view's horizontal padding grows until its content is at most that wide and centred; the padding
 * the layout asked for stays the minimum, so on a screen only slightly wider than the cap the
 * margins are the same as on a phone.
 *
 * Works on any view whose children fill its width (a ScrollView's column, a RecyclerView with
 * clipToPadding=false, the tab bar). The padding follows the view's width, so it also tracks a
 * fold or unfold without a recreate.
 */
object ContentWidth {

    /**
     * [contentInset] is horizontal padding the view's children already carry (a RecyclerView whose
     * rows pad themselves): the cap counts it as part of the margin, so the rows' text lines up
     * with sibling views that were capped without one.
     */
    fun limit(view: View, contentInset: Int = 0) {
        val max = view.resources.getDimensionPixelSize(R.dimen.pb_content_max_width)
        if (max <= 0) return
        val baseStart = view.paddingStart
        val baseEnd = view.paddingEnd
        fun apply(width: Int) {
            if (width <= 0) return
            val side = ((width - max) / 2 - contentInset).coerceAtLeast(0)
            val start = maxOf(baseStart, side)
            val end = maxOf(baseEnd, side)
            if (view.paddingStart != start || view.paddingEnd != end) {
                view.setPaddingRelative(start, view.paddingTop, end, view.paddingBottom)
            }
        }
        // Padding is changed after the layout pass that reported the width, not during it: a
        // requestLayout from inside a layout pass is not honoured for every parent (a
        // LinearLayout row dropped it), and the deferred one always is.
        view.addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
            val width = right - left
            if (width != oldRight - oldLeft || v.paddingStart < ((width - max) / 2 - contentInset)) v.post { apply(v.width) }
        }
        apply(view.width)
    }
}
