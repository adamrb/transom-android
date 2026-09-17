package cloud.adamrb.transom.export

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import cloud.adamrb.transom.R
import cloud.adamrb.transom.common.AppLog
import java.io.File

/**
 * Clipboard and share-sheet plumbing shared by the file detail screen and the Library WebView
 * bridge, so both entry points behave identically (same label, same toast, same export dir).
 *
 * Exports land in `cacheDir/exports/` (listed in res/xml/file_paths.xml) and are handed out
 * through the app's FileProvider. Cache, not files: the export is a throwaway copy the share
 * target reads once; the OS may reclaim it and nothing depends on it later.
 */
object TranscriptShare {

    private const val TAG = "TranscriptShare"

    const val EXPORTS_DIR = "exports"

    /** Exports older than this are purged on the next export so the cache does not pile up. */
    private const val STALE_AFTER_MS = 24L * 60 * 60 * 1000

    const val MIME_MARKDOWN = "text/markdown"

    /** Put [text] on the clipboard and confirm with a short toast. Main thread only (Toast). */
    fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Transcript", text))
        Toast.makeText(context, R.string.transcript_copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * Write [markdown] to `cacheDir/exports/<fileName>` and return the file. [fileName] must
     * already be sanitized (see [ExportFileName]); this only creates the directory and writes.
     * Safe to call off the main thread.
     */
    fun writeExport(context: Context, fileName: String, markdown: String): File {
        val dir = File(context.cacheDir, EXPORTS_DIR).apply { mkdirs() }
        purgeStale(dir)
        return File(dir, fileName).apply { writeText(markdown, Charsets.UTF_8) }
    }

    /**
     * Chooser intent for an exported markdown file. EXTRA_STREAM carries the file for targets
     * that accept attachments (Drive, mail, Obsidian); EXTRA_TEXT carries the same markdown so
     * text-only targets (Keep, messaging) still receive the content; EXTRA_SUBJECT gives mail
     * clients a subject line.
     */
    fun shareIntent(context: Context, file: File, title: String, markdown: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_MARKDOWN
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, markdown)
            // ClipData lets the chooser itself (and targets on API 21+) read the stream under
            // the granted permission; some launchers drop EXTRA_STREAM grants without it.
            clipData = ClipData.newRawUri(title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, context.getString(R.string.export_markdown))
    }

    /** Write then open the share sheet in one step. Main thread (startActivity). */
    fun share(context: Context, fileName: String, title: String, markdown: String) {
        val file = writeExport(context, fileName, markdown)
        context.startActivity(shareIntent(context, file, title, markdown))
    }

    private fun purgeStale(dir: File) {
        val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < cutoff && !f.delete()) {
                AppLog.w(TAG, "could not delete stale export ${f.name}")
            }
        }
    }
}
