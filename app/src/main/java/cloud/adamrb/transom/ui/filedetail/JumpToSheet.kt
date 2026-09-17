package cloud.adamrb.transom.ui.filedetail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import cloud.adamrb.transom.R

/**
 * The Jump to sheet: bookmarks, speaker changes and ten-minute marks, each a row that reveals
 * its paragraph and plays from there. The host activity supplies the items and takes the tap
 * ([Host]), so a rotation rebuilds the sheet from the live screen rather than a stale copy.
 */
class JumpToSheet : BottomSheetDialogFragment() {

    interface Host {
        fun jumpToItems(): List<JumpToItem>
        fun onJumpTo(item: JumpToItem)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.sheet_jump_to, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val host = activity as? Host
        if (host == null) {
            dismissAllowingStateLoss()
            return
        }
        val list = view.findViewById<RecyclerView>(R.id.jumpToList)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = Adapter(rowsFor(host.jumpToItems())) { item ->
            dismiss()
            host.onJumpTo(item)
        }
    }

    /** Sheet rows: a section label before each kind that has entries, in the sheet's order. */
    private sealed class Row {
        data class Section(val titleRes: Int) : Row()
        data class Entry(val item: JumpToItem) : Row()
    }

    private fun rowsFor(items: List<JumpToItem>): List<Row> {
        val rows = mutableListOf<Row>()
        for ((kind, titleRes) in SECTIONS) {
            val entries = items.filter { it.kind == kind }
            if (entries.isEmpty()) continue
            rows += Row.Section(titleRes)
            entries.forEach { rows += Row.Entry(it) }
        }
        return rows
    }

    private class Adapter(private val rows: List<Row>, private val onTap: (JumpToItem) -> Unit) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = if (rows[position] is Row.Section) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) object : RecyclerView.ViewHolder(inflater.inflate(R.layout.item_jump_to_header, parent, false)) {}
            else object : RecyclerView.ViewHolder(inflater.inflate(R.layout.item_jump_to, parent, false)) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Section -> (holder.itemView as TextView).setText(row.titleRes)
                is Row.Entry -> {
                    val item = row.item
                    holder.itemView.findViewById<TextView>(R.id.jumpTime).text = item.timeLabel
                    val label = holder.itemView.findViewById<TextView>(R.id.jumpLabel)
                    val text = when (item.kind) {
                        JumpToItem.Kind.BOOKMARK -> item.label.ifEmpty { holder.itemView.context.getString(R.string.highlight_no_speech) }
                        else -> item.label
                    }
                    label.text = text
                    label.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                    holder.itemView.contentDescription =
                        if (text.isEmpty()) holder.itemView.context.getString(R.string.detail_jump_time_mark_cd, item.timeLabel) else null
                    holder.itemView.setOnClickListener { onTap(item) }
                }
            }
        }
    }

    companion object {
        const val TAG = "jump_to"

        private val SECTIONS = listOf(
            JumpToItem.Kind.BOOKMARK to R.string.detail_jump_bookmarks,
            JumpToItem.Kind.SPEAKER to R.string.detail_jump_speakers,
            JumpToItem.Kind.TIME_MARK to R.string.detail_jump_time_marks
        )
    }
}
