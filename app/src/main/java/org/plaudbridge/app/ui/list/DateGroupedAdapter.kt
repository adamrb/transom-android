package org.plaudbridge.app.ui.list

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.plaudbridge.app.R

/**
 * RecyclerView adapter for a date-grouped recording list: `item_date_header` rows between
 * `item_file_row` rows, exactly as the Files tab draws them. Subclasses only decide what the two
 * labels of a row say and what tapping does, so the Library tab cannot drift visually from Files.
 */
abstract class DateGroupedAdapter<T>(
    private val timestamp: (T) -> Long
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }

    private var rows: List<DateGrouping.Row<T>> = emptyList()

    /** Replace the list; grouping and newest-first ordering happen here. */
    fun submit(items: List<T>) {
        rows = DateGrouping.group(items, timestamp = timestamp)
        notifyDataSetChanged()
    }

    /** Primary label of a row (the recording's display name). */
    protected abstract fun rowName(context: android.content.Context, item: T): String

    /** Secondary label of a row (date, time, duration and whatever state the list adds). */
    protected abstract fun rowMeta(context: android.content.Context, item: T): String

    protected abstract fun onRowTapped(item: T)

    /** Long-press hook; default does nothing so Files keeps its current behavior. */
    protected open fun onRowLongPressed(item: T): Boolean = false

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is DateGrouping.Row.Header -> TYPE_HEADER
        is DateGrouping.Row.Item -> TYPE_ITEM
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_date_header, parent, false))
            else -> ItemViewHolder(inflater.inflate(R.layout.item_file_row, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is DateGrouping.Row.Header -> (holder as HeaderViewHolder).bind(row.title, position == 0)
            is DateGrouping.Row.Item -> bindItem(holder, row.item)
        }
    }

    private fun bindItem(holder: RecyclerView.ViewHolder, item: T) {
        holder.itemView.findViewById<TextView>(R.id.fileNameLabel).text = rowName(holder.itemView.context, item)
        holder.itemView.findViewById<TextView>(R.id.fileMetaLabel).text = rowMeta(holder.itemView.context, item)
        holder.itemView.setOnClickListener { onRowTapped(item) }
        holder.itemView.setOnLongClickListener { onRowLongPressed(item) }
    }

    private class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(title: String, isFirst: Boolean) {
            val label = itemView.findViewById<TextView>(R.id.dateHeaderLabel)
            label.text = title
            // Match iOS: 40dp gap before each group, none before the first.
            val density = itemView.resources.displayMetrics.density
            val topPad = if (isFirst) 0 else (40 * density).toInt()
            label.setPadding(label.paddingLeft, topPad, label.paddingRight, label.paddingBottom)
        }
    }

    /** Plain holder: the row is bound in [bindItem] so no cast to a generic inner class is needed. */
    private class ItemViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
