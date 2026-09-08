package org.plaudbridge.app.ui.list

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.plaudbridge.app.R
import java.util.concurrent.Executor

/**
 * RecyclerView adapter for a date-grouped recording list: `item_date_header` rows between
 * `item_file_row` rows. Subclasses decide what the labels of a row say and what tapping does.
 *
 * A [ListAdapter] with a [DiffUtil] callback keyed by [key], so the 5 s poll while a recording
 * is being transcribed moves only the rows whose state changed: a status word ticking from
 * "Transcribing · 42%" to "Transcribing · 47%" is a payload-based rebind of the labels, not a
 * `notifyDataSetChanged` that redraws every row and drops the scroll position. Rows that did
 * not change are not touched at all.
 *
 * [diffExecutor] is a test seam: the default diffs on a background thread, tests pass a direct
 * executor so the update lands as soon as the main looper idles.
 */
abstract class DateGroupedAdapter<T : Any>(
    private val timestamp: (T) -> Long,
    key: (T) -> Any,
    diffExecutor: Executor? = null
) : ListAdapter<DateGrouping.Row<T>, RecyclerView.ViewHolder>(config(RowDiff(key), diffExecutor)) {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1

        /** Change payload: same recording, different labels (status, title, snippet). */
        const val PAYLOAD_LABELS = "labels"

        private fun <T : Any> config(diff: RowDiff<T>, executor: Executor?): AsyncDifferConfig<DateGrouping.Row<T>> {
            val builder = AsyncDifferConfig.Builder(diff)
            if (executor != null) builder.setBackgroundThreadExecutor(executor)
            return builder.build()
        }
    }

    /**
     * Identity: headers by their title (one per day), items by [key]. Content: the data classes'
     * own equality. Every item-to-item change is a labels payload, which also tells the item
     * animator to reuse the holder instead of cross-fading a new one in.
     */
    class RowDiff<T : Any>(private val key: (T) -> Any) : DiffUtil.ItemCallback<DateGrouping.Row<T>>() {
        override fun areItemsTheSame(a: DateGrouping.Row<T>, b: DateGrouping.Row<T>): Boolean = when {
            a is DateGrouping.Row.Header && b is DateGrouping.Row.Header -> a.title == b.title
            a is DateGrouping.Row.Item && b is DateGrouping.Row.Item -> key(a.item) == key(b.item)
            else -> false
        }

        override fun areContentsTheSame(a: DateGrouping.Row<T>, b: DateGrouping.Row<T>): Boolean = a == b

        override fun getChangePayload(a: DateGrouping.Row<T>, b: DateGrouping.Row<T>): Any? =
            if (a is DateGrouping.Row.Item && b is DateGrouping.Row.Item) PAYLOAD_LABELS else null
    }

    /** Replace the list; grouping and newest-first ordering happen here. */
    fun submit(items: List<T>) {
        submitList(DateGrouping.group(items, timestamp = timestamp))
    }

    /** The item at [position], or null for a header. */
    fun itemAt(position: Int): T? = (getItem(position) as? DateGrouping.Row.Item<T>)?.item

    /** Primary label of a row (the recording's display name). */
    protected abstract fun rowName(context: Context, item: T): CharSequence

    /** Secondary label of a row (date, time, duration and whatever state the list adds). */
    protected abstract fun rowMeta(context: Context, item: T): CharSequence

    /** Optional third line (a search snippet); null hides it. */
    protected open fun rowSnippet(context: Context, item: T): CharSequence? = null

    protected abstract fun onRowTapped(item: T)

    /** Long-press hook; default does nothing. */
    protected open fun onRowLongPressed(item: T): Boolean = false

    /** The trailing ⋮ of a row; default hides the button. Return true from [hasRowActions] to show it. */
    protected open fun onRowMore(item: T) {}

    protected open fun hasRowActions(item: T): Boolean = false

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is DateGrouping.Row.Header -> TYPE_HEADER
        is DateGrouping.Row.Item -> TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_date_header, parent, false))
            else -> ItemViewHolder(inflater.inflate(R.layout.item_file_row, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is DateGrouping.Row.Header -> (holder as HeaderViewHolder).bind(row.title, position == 0)
            is DateGrouping.Row.Item -> bindItem(holder as ItemViewHolder, row.item, full = true)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        val row = getItem(position)
        if (payloads.isEmpty() || row !is DateGrouping.Row.Item) {
            onBindViewHolder(holder, position)
            return
        }
        // Labels only: the listeners below read the current item through the holder's position.
        bindItem(holder as ItemViewHolder, row.item, full = false)
    }

    private fun bindItem(holder: ItemViewHolder, item: T, full: Boolean) {
        val context = holder.itemView.context
        holder.name.text = rowName(context, item)
        holder.meta.text = rowMeta(context, item)
        val snippet = rowSnippet(context, item)
        holder.snippet.text = snippet
        holder.snippet.visibility = if (snippet.isNullOrEmpty()) View.GONE else View.VISIBLE
        holder.more.visibility = if (hasRowActions(item)) View.VISIBLE else View.GONE
        if (!full) return
        holder.itemView.setOnClickListener { currentItem(holder)?.let(::onRowTapped) }
        holder.itemView.setOnLongClickListener { currentItem(holder)?.let(::onRowLongPressed) ?: false }
        holder.more.setOnClickListener { currentItem(holder)?.let(::onRowMore) }
    }

    /** The item [holder] shows right now (positions move as the diff lands). */
    private fun currentItem(holder: RecyclerView.ViewHolder): T? {
        val position = holder.bindingAdapterPosition
        return if (position == RecyclerView.NO_POSITION) null else itemAt(position)
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

    private class ItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.fileNameLabel)
        val meta: TextView = view.findViewById(R.id.fileMetaLabel)
        val snippet: TextView = view.findViewById(R.id.fileSnippetLabel)
        val more: ImageView = view.findViewById(R.id.moreButton)
    }
}
