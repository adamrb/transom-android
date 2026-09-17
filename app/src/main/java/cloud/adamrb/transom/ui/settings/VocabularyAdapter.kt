package cloud.adamrb.transom.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cloud.adamrb.transom.R
import cloud.adamrb.transom.databinding.ItemVocabTermBinding
import cloud.adamrb.transom.models.VocabEntry

/**
 * Rows of the custom vocabulary list: the term, the mis-hearings it corrects, a remove button.
 * Tapping the row edits it. Diffed by term (the server's own case-insensitive key).
 */
class VocabularyAdapter(
    private val onEdit: (VocabEntry) -> Unit,
    private val onRemove: (VocabEntry) -> Unit
) : ListAdapter<VocabEntry, VocabularyAdapter.Holder>(
    // Diff on the calling thread: the list is at most a few hundred short entries, and a
    // synchronous diff means the filter result is on screen (and asserted in tests) right away.
    AsyncDifferConfig.Builder(DIFF).setBackgroundThreadExecutor { it.run() }.build()
) {

    class Holder(val binding: ItemVocabTermBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemVocabTermBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = getItem(position)
        val b = holder.binding
        b.termLabel.text = entry.term
        if (entry.aliases.isEmpty()) {
            b.aliasesLabel.visibility = View.GONE
        } else {
            b.aliasesLabel.visibility = View.VISIBLE
            b.aliasesLabel.text = b.root.context.getString(
                R.string.vocabulary_aliases_fmt, entry.aliases.joinToString(", ")
            )
        }
        b.root.setOnClickListener { onEdit(entry) }
        b.removeButton.setOnClickListener { onRemove(entry) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<VocabEntry>() {
            override fun areItemsTheSame(a: VocabEntry, b: VocabEntry) = a.term.equals(b.term, ignoreCase = true)
            override fun areContentsTheSame(a: VocabEntry, b: VocabEntry) = a == b
        }
    }
}
