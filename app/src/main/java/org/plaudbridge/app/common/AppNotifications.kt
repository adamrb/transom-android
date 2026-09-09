package org.plaudbridge.app.common

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.plaudbridge.app.R
import org.plaudbridge.app.models.Delivery
import org.plaudbridge.app.ui.filedetail.FileDetailActivity

/**
 * The app's user-facing notifications: "your transcript is ready" and "an automation finished".
 * Each kind has its own channel so the user can turn one off in the system settings and keep
 * the other (Settings > Notifications opens that screen). The recorder-connection channel of the
 * foreground service and the media notification of PlaybackService are separate and stay as
 * they are.
 *
 * Both kinds are DEFAULT importance: a sound and a status-bar icon, no heads-up banner. The user
 * asked for them to know the pipeline is working, not to be interrupted by it.
 *
 * Posting is best-effort: without POST_NOTIFICATIONS (API 33+) or with notifications disabled
 * for the app, [post] simply does nothing. Every notification opens the recording it is about.
 */
object AppNotifications {

    private const val TAG = "AppNotifications"

    const val CHANNEL_TRANSCRIPTS = "transcripts"
    const val CHANNEL_AUTOMATIONS = "automations"

    /** Tag prefixes keep one notification per recording (transcripts) or per hand-off (automations). */
    const val TAG_TRANSCRIPT = "transcript:"
    const val TAG_AUTOMATION = "automation:"
    const val TAG_ROUTING_RUN = "routing-run:"

    /** One id per tag is enough: the tag is what makes notifications distinct. */
    private const val NOTIFICATION_ID = 1

    /** Longest body line shown before the text is cut with an ellipsis. */
    const val BODY_MAX_CHARS = 160

    /** Create (or update the names of) both channels. Idempotent; call at process start. */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TRANSCRIPTS,
                context.getString(R.string.notif_channel_transcripts_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = context.getString(R.string.notif_channel_transcripts_desc) }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_AUTOMATIONS,
                context.getString(R.string.notif_channel_automations_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = context.getString(R.string.notif_channel_automations_desc) }
        )
    }

    /** The app may post: notifications are on for the app and (API 33+) the permission is granted. */
    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * The system screen where each channel can be switched on or off: the app's notification
     * settings on API 26+, the app details page before channels existed.
     */
    fun settingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:" + context.packageName))
        }

    /**
     * A transcript (and with it the title and summary) landed for a recording. The notification
     * is titled with the recording's name and shows the first line of the summary; a recording
     * in which the server heard nothing says so instead.
     */
    fun transcriptReady(context: Context, fileId: String?, serverId: String, title: String, transcriptJson: String) {
        val noSpeech = org.plaudbridge.app.models.ServerRecording.transcriptSaysNoSpeech(transcriptJson)
        val body = if (noSpeech) {
            context.getString(R.string.notif_transcript_no_speech)
        } else {
            summaryLine(transcriptJson) ?: context.getString(R.string.notif_transcript_ready_body)
        }
        val notification = builder(context, CHANNEL_TRANSCRIPTS, R.drawable.ic_recordings, fileId, serverId)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText(context.getString(R.string.notif_transcript_ready))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .build()
        post(context, TAG_TRANSCRIPT + serverId, notification)
    }

    /**
     * A hand-off to an automation reached its outcome. Titled with the route ("Work meetings"),
     * the body is what the agent reported ("Created: Work/Meetings/2026/Q3/…") or, for a failed
     * hand-off, what went wrong; the recording's name sits in the header line.
     */
    fun automationFinished(context: Context, fileId: String?, serverId: String, recordingTitle: String, delivery: Delivery) {
        val route = delivery.routeName.ifBlank { context.getString(R.string.automations) }
        val failed = delivery.status == Delivery.STATUS_FAILED || delivery.resultStatus == Delivery.RESULT_FAILED
        val unknown = !failed && delivery.resultStatus == Delivery.RESULT_UNKNOWN
        val title = when {
            failed -> context.getString(R.string.notif_automation_failed_fmt, route)
            unknown -> context.getString(R.string.notif_automation_no_result_fmt, route)
            else -> context.getString(R.string.notif_automation_done_fmt, route)
        }
        val body = when {
            failed -> delivery.lastError ?: delivery.resultSummary ?: context.getString(R.string.notif_automation_failed_body)
            unknown -> context.getString(R.string.notif_automation_no_result_body)
            else -> delivery.resultSummary ?: context.getString(R.string.notif_automation_done_body)
        }.trim()
        val notification = builder(context, CHANNEL_AUTOMATIONS, R.drawable.ic_bolt, fileId, serverId)
            .setContentTitle(title)
            .setContentText(oneLine(body))
            .setSubText(recordingTitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .build()
        post(context, TAG_AUTOMATION + delivery.id, notification)
    }

    /**
     * The router looked at a recording and applied nothing (no route matched), or could not run
     * at all ([error] set). One notification per router run, so the user learns that the
     * recording was considered and left alone rather than wondering whether anything happened.
     */
    fun automationsSkipped(context: Context, fileId: String?, serverId: String, recordingTitle: String, runId: String, error: String?) {
        val title = if (error == null) context.getString(R.string.notif_automations_none) else context.getString(R.string.notif_automations_error)
        val body = error?.trim()?.takeIf { it.isNotEmpty() } ?: context.getString(R.string.notif_automations_none_body)
        val notification = builder(context, CHANNEL_AUTOMATIONS, R.drawable.ic_bolt, fileId, serverId)
            .setContentTitle(title)
            .setContentText(oneLine(body))
            .setSubText(recordingTitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .build()
        post(context, TAG_ROUTING_RUN + runId, notification)
    }

    private fun builder(context: Context, channel: String, icon: Int, fileId: String?, serverId: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(icon)
            .setContentIntent(openRecording(context, fileId, serverId))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

    /** Tapping opens the recording; the request code keeps recordings' intents distinct. */
    private fun openRecording(context: Context, fileId: String?, serverId: String): PendingIntent {
        val intent = Intent(context, FileDetailActivity::class.java).apply {
            fileId?.let { putExtra(FileDetailActivity.EXTRA_FILE_ID, it) }
            putExtra(FileDetailActivity.EXTRA_SERVER_RECORDING_ID, serverId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(context, serverId.hashCode(), intent, flags)
    }

    private fun post(context: Context, tag: String, notification: android.app.Notification) {
        if (!canPost(context)) {
            AppLog.i(TAG, "Notifications are off; not posting $tag")
            return
        }
        try {
            NotificationManagerCompat.from(context).notify(tag, NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // The permission was revoked between the check and the call.
            AppLog.w(TAG, "Could not post $tag", e)
        }
    }

    /**
     * The first line of a transcript document's summary that is prose: headings, empty lines and
     * list markers are skipped or stripped, bold markers removed, and the result cut to
     * [BODY_MAX_CHARS]. Null when the document has no usable summary.
     */
    fun summaryLine(transcriptJson: String): String? {
        val summary = try {
            (JSONObject(transcriptJson).opt("summary") as? String)
        } catch (e: Exception) {
            null
        } ?: return null
        for (raw in summary.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val text = line
                .removePrefix("- ").removePrefix("* ").removePrefix("• ")
                .replace("**", "")
                .trim()
            if (text.isNotEmpty()) return oneLine(text)
        }
        return null
    }

    /** Collapse whitespace and cut to [BODY_MAX_CHARS] with an ellipsis. */
    fun oneLine(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= BODY_MAX_CHARS) flat else flat.take(BODY_MAX_CHARS - 1).trimEnd() + "…"
    }
}
