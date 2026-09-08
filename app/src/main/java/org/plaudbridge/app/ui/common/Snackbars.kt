package org.plaudbridge.app.ui.common

import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar

/**
 * An activity that knows where snackbars go (above its tab bar, not under it). Fragments ask
 * their host instead of anchoring to their own view, so the message sits in the same place
 * whichever tab is showing.
 */
interface SnackbarHost {
    fun showSnackbar(message: String, actionLabel: String? = null, action: (() -> Unit)? = null)
}

/** Show through the host when there is one, else on the fragment's own view. */
fun Fragment.showSnackbar(message: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
    val host = activity as? SnackbarHost
    if (host != null) {
        host.showSnackbar(message, actionLabel, action)
        return
    }
    val root = view ?: return
    Snackbar.make(root, message, if (action != null) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT).apply {
        if (actionLabel != null && action != null) setAction(actionLabel) { action() }
    }.show()
}
