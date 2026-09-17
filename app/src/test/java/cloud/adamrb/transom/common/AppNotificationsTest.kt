package cloud.adamrb.transom.common

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import cloud.adamrb.transom.R
import cloud.adamrb.transom.models.Delivery
import cloud.adamrb.transom.storage.RecordingStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * AppNotifications: two channels, one notification per recording (transcripts) or per hand-off
 * (automations), user words in title and body, and the summary-line extraction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AppNotificationsTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RecordingStore.init(context)
        manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        AppNotifications.ensureChannels(context)
    }

    private fun delivery(
        id: String = "d1",
        route: String = "Work meetings",
        status: String = Delivery.STATUS_OK,
        resultStatus: String? = Delivery.RESULT_DONE,
        summary: String? = "Created: Work/Meetings/2026/Q3/2026-09-09 - Standup.md",
        lastError: String? = null,
        actionType: String = Delivery.ACTION_WEBHOOK
    ) = Delivery(
        id = id, routerRunId = "run-1", routeName = route, actionType = actionType, status = status,
        attempts = 1, lastError = lastError, createdAt = 1000L, resultStatus = resultStatus,
        resultSummary = summary, resultAt = 2000L
    )

    @Test
    fun ensureChannelsCreatesBothWithUserNames() {
        val transcripts = manager.getNotificationChannel(AppNotifications.CHANNEL_TRANSCRIPTS)
        val automations = manager.getNotificationChannel(AppNotifications.CHANNEL_AUTOMATIONS)
        assertNotNull(transcripts)
        assertNotNull(automations)
        assertEquals("Transcripts", transcripts.name)
        assertEquals("Automations", automations.name)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, transcripts.importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, automations.importance)
        // Idempotent: a second call neither throws nor duplicates.
        AppNotifications.ensureChannels(context)
        assertEquals(2, manager.notificationChannels.count { it.id in setOf(AppNotifications.CHANNEL_TRANSCRIPTS, AppNotifications.CHANNEL_AUTOMATIONS) })
    }

    @Test
    fun transcriptReadyPostsOnTranscriptsChannelWithTitleAndSummaryLine() {
        val json = """{"text":"...","title":"Standup with Sam","summary":"## Summary\n\n**Sam** agreed to ship the fix on Friday.\n- Item"}"""
        AppNotifications.transcriptReady(context, "file-1", "srv-1", "Standup with Sam", json)

        val posted = shadowOf(manager).allNotifications
        assertEquals(1, posted.size)
        val n = posted.first()
        assertEquals(AppNotifications.CHANNEL_TRANSCRIPTS, n.channelId)
        assertEquals("Standup with Sam", n.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
        assertEquals("Sam agreed to ship the fix on Friday.", n.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
        assertEquals("Transcript ready", n.extras.getCharSequence(NotificationCompat.EXTRA_SUB_TEXT).toString())
        assertNotNull("tapping opens the recording", n.contentIntent)
        assertNotNull(shadowOf(manager).getNotification(AppNotifications.TAG_TRANSCRIPT + "srv-1", 1))
    }

    @Test
    fun transcriptReadyForNoSpeechSaysSo() {
        AppNotifications.transcriptReady(context, null, "srv-2", "Recording", """{"no_speech":true,"title":null,"summary":null}""")
        val n = shadowOf(manager).allNotifications.single()
        assertEquals(context.getString(R.string.notif_transcript_no_speech), n.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
    }

    @Test
    fun transcriptReadyTwiceForOneRecordingReplacesRatherThanStacks() {
        val json = """{"title":"T","summary":"First"}"""
        AppNotifications.transcriptReady(context, null, "srv-1", "T", json)
        AppNotifications.transcriptReady(context, null, "srv-1", "T", """{"title":"T","summary":"Second"}""")
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertEquals("Second", shadowOf(manager).allNotifications.single().extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
    }

    @Test
    fun automationDoneUsesRouteNameAndAgentSummary() {
        AppNotifications.automationFinished(context, "file-1", "srv-1", "Standup with Sam", delivery())
        val n = shadowOf(manager).allNotifications.single()
        assertEquals(AppNotifications.CHANNEL_AUTOMATIONS, n.channelId)
        assertEquals("Work meetings done", n.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
        assertEquals("Created: Work/Meetings/2026/Q3/2026-09-09 - Standup.md", n.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
        assertEquals("Standup with Sam", n.extras.getCharSequence(NotificationCompat.EXTRA_SUB_TEXT).toString())
        assertNotNull(shadowOf(manager).getNotification(AppNotifications.TAG_AUTOMATION + "d1", 1))
    }

    @Test
    fun automationFailedShowsTheError() {
        AppNotifications.automationFinished(
            context, null, "srv-1", "Standup",
            delivery(id = "d2", route = "Ask Claude", status = Delivery.STATUS_FAILED, resultStatus = null, summary = null, lastError = "The server said no.")
        )
        val n = shadowOf(manager).allNotifications.single()
        assertEquals("Ask Claude failed", n.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
        assertEquals("The server said no.", n.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
    }

    @Test
    fun automationUnknownResultSaysNoResult() {
        AppNotifications.automationFinished(context, null, "srv-1", "Standup", delivery(id = "d3", resultStatus = Delivery.RESULT_UNKNOWN, summary = "No result was reported"))
        val n = shadowOf(manager).allNotifications.single()
        assertEquals("Work meetings gave no result", n.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
        assertEquals(context.getString(R.string.notif_automation_no_result_body), n.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
    }

    @Test
    fun automationsSkippedAndRouterError() {
        AppNotifications.automationsSkipped(context, null, "srv-1", "Standup", "run-1", null)
        AppNotifications.automationsSkipped(context, null, "srv-1", "Standup", "run-2", "Model timed out")
        val all = shadowOf(manager).allNotifications
        assertEquals(2, all.size)
        val none = shadowOf(manager).getNotification(AppNotifications.TAG_ROUTING_RUN + "run-1", 1)
        val err = shadowOf(manager).getNotification(AppNotifications.TAG_ROUTING_RUN + "run-2", 1)
        assertEquals("No automation matched", none.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
        assertEquals("Automations couldn't run", err.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
        assertEquals("Model timed out", err.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
    }

    @Test
    fun nothingIsPostedWithoutThePermission() {
        shadowOf(context as android.app.Application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        AppNotifications.transcriptReady(context, null, "srv-1", "T", """{"title":"T"}""")
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun settingsIntentTargetsThisAppsChannels() {
        val intent = AppNotifications.settingsIntent(context)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun summaryLineSkipsHeadingsAndMarkup() {
        assertEquals("Plan the launch.", AppNotifications.summaryLine("""{"summary":"# Title\n\n## Summary\n- **Plan** the launch.\n- Second"}"""))
        assertNull(AppNotifications.summaryLine("""{"summary":"## Only a heading"}"""))
        assertNull(AppNotifications.summaryLine("""{"text":"no summary"}"""))
        assertNull(AppNotifications.summaryLine("not json"))
    }

    @Test
    fun oneLineCollapsesWhitespaceAndCuts() {
        assertEquals("a b c", AppNotifications.oneLine("a\n b\t\tc "))
        val long = "x".repeat(AppNotifications.BODY_MAX_CHARS + 20)
        val cut = AppNotifications.oneLine(long)
        assertEquals(AppNotifications.BODY_MAX_CHARS, cut.length)
        assertTrue(cut.endsWith("…"))
    }
}
