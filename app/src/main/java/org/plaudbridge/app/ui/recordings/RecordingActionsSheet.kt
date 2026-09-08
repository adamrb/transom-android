package org.plaudbridge.app.ui.recordings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.plaudbridge.app.databinding.SheetRecordingActionsBinding

/**
 * The actions for one row of the Recordings list, as a bottom sheet with icons: Rename, Retry
 * upload, Re-transcribe, Remove from phone, and after a divider Delete in red. Only the actions
 * that apply to the row are shown (see [Args]). The choice comes back through the fragment
 * result API under [REQUEST_KEY] with [KEY_ACTION] and the row's [KEY_ITEM] key, so the list
 * looks the item up again rather than holding a stale copy across a recreation.
 */
class RecordingActionsSheet : BottomSheetDialogFragment() {

    enum class Action { RENAME, RETRY_UPLOAD, RETRANSCRIBE, REMOVE_FROM_PHONE, DELETE }

    /** What the sheet shows, decided by the caller from the row. */
    data class Args(
        val itemKey: String,
        val title: String,
        val canRetryUpload: Boolean,
        val canRetranscribe: Boolean,
        val canRemoveFromPhone: Boolean
    ) {
        fun toBundle() = bundleOf(
            ARG_KEY to itemKey, ARG_TITLE to title, ARG_RETRY_UPLOAD to canRetryUpload,
            ARG_RETRANSCRIBE to canRetranscribe, ARG_REMOVE to canRemoveFromPhone
        )

        companion object {
            fun from(bundle: Bundle) = Args(
                itemKey = bundle.getString(ARG_KEY) ?: "",
                title = bundle.getString(ARG_TITLE) ?: "",
                canRetryUpload = bundle.getBoolean(ARG_RETRY_UPLOAD),
                canRetranscribe = bundle.getBoolean(ARG_RETRANSCRIBE),
                canRemoveFromPhone = bundle.getBoolean(ARG_REMOVE)
            )
        }
    }

    private var _binding: SheetRecordingActionsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetRecordingActionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = Args.from(requireArguments())
        binding.sheetTitle.text = args.title
        binding.actionRetryUpload.visibility = if (args.canRetryUpload) View.VISIBLE else View.GONE
        binding.actionRetranscribe.visibility = if (args.canRetranscribe) View.VISIBLE else View.GONE
        binding.actionRemoveFromPhone.visibility = if (args.canRemoveFromPhone) View.VISIBLE else View.GONE

        binding.actionRename.setOnClickListener { choose(Action.RENAME, args.itemKey) }
        binding.actionRetryUpload.setOnClickListener { choose(Action.RETRY_UPLOAD, args.itemKey) }
        binding.actionRetranscribe.setOnClickListener { choose(Action.RETRANSCRIBE, args.itemKey) }
        binding.actionRemoveFromPhone.setOnClickListener { choose(Action.REMOVE_FROM_PHONE, args.itemKey) }
        binding.actionDelete.setOnClickListener { choose(Action.DELETE, args.itemKey) }
    }

    private fun choose(action: Action, itemKey: String) {
        parentFragmentManager.setFragmentResult(REQUEST_KEY, bundleOf(KEY_ACTION to action.name, KEY_ITEM to itemKey))
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "RecordingActionsSheet"
        const val REQUEST_KEY = "recording_action"
        const val KEY_ACTION = "action"
        const val KEY_ITEM = "item"

        private const val ARG_KEY = "key"
        private const val ARG_TITLE = "title"
        private const val ARG_RETRY_UPLOAD = "retry_upload"
        private const val ARG_RETRANSCRIBE = "retranscribe"
        private const val ARG_REMOVE = "remove"

        /** The actions that apply to [item], in the order the sheet lists them. */
        fun argsFor(item: RecordingItem, title: String) = Args(
            itemKey = item.key,
            title = title,
            canRetryUpload = item.canRetryUpload,
            canRetranscribe = item.serverId != null,
            canRemoveFromPhone = item.canRemoveFromPhone
        )

        fun newInstance(args: Args) = RecordingActionsSheet().apply { arguments = args.toBundle() }

        /** Parse a fragment result; null when it is not one of ours. */
        fun parseResult(result: Bundle): Pair<Action, String>? {
            val action = result.getString(KEY_ACTION)?.let { name -> Action.values().firstOrNull { it.name == name } }
                ?: return null
            val key = result.getString(KEY_ITEM) ?: return null
            return action to key
        }
    }
}
