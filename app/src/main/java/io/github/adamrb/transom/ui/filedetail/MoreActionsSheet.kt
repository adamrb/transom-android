package io.github.adamrb.transom.ui.filedetail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.R as MaterialR
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.github.adamrb.transom.R
import io.github.adamrb.transom.ui.common.themeColor

/**
 * The detail screen's More sheet: one row per applicable action, Delete last in the destructive
 * colour behind a divider. The host builds the menu (visibility rules live there, shared with
 * the tests) and handles the tap; the sheet only lays the rows out.
 */
class MoreActionsSheet : BottomSheetDialogFragment() {

    interface Host {
        /** The recording's name for the sheet's caption, or null for none. */
        fun moreSheetTitle(): String?
        /** The menu with visibility and enabled state applied, or null when there is nothing to offer. */
        fun buildMoreMenu(): Menu?
        fun onMenuAction(itemId: Int): Boolean
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.sheet_more_actions, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val host = activity as? Host
        val menu = host?.buildMoreMenu()
        if (host == null || menu == null) {
            dismissAllowingStateLoss()
            return
        }
        val title = view.findViewById<TextView>(R.id.moreTitle)
        val caption = host.moreSheetTitle()
        title.text = caption
        title.visibility = if (caption.isNullOrBlank()) View.GONE else View.VISIBLE
        val list = view.findViewById<LinearLayout>(R.id.actionsList)
        val inflater = LayoutInflater.from(view.context)
        val red = view.themeColor(MaterialR.attr.colorError)
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            if (!item.isVisible) continue
            val destructive = item.itemId == R.id.action_delete
            if (destructive && list.childCount > 0) {
                list.addView(View(view.context).apply {
                    setBackgroundColor(view.themeColor(R.attr.pbColorDivider))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                    val m = (8 * resources.displayMetrics.density).toInt()
                    topMargin = m
                    bottomMargin = m
                })
            }
            val row = inflater.inflate(R.layout.item_sheet_action, list, false) as TextView
            row.id = item.itemId
            row.text = item.title
            if (destructive) row.setTextColor(red)
            row.isEnabled = item.isEnabled
            row.alpha = if (item.isEnabled) 1f else 0.38f
            row.setOnClickListener {
                dismiss()
                host.onMenuAction(item.itemId)
            }
            list.addView(row)
        }
    }

    companion object {
        const val TAG = "more_actions"
    }
}
