package org.plaudbridge.app.ui.library

import androidx.appcompat.app.AppCompatDelegate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EmbeddedUrlTest {
    @Test
    fun appendsEmbeddedFlagToBareOrigin() {
        assertEquals("https://plaud.example.com?embedded=1", WebDashboardActivity.embeddedUrl("https://plaud.example.com"))
    }

    @Test
    fun appendsEmbeddedFlagKeepingPathAndExistingQuery() {
        assertEquals(
            "https://plaud.example.com/dash?x=1&embedded=1",
            WebDashboardActivity.embeddedUrl("https://plaud.example.com/dash?x=1"),
        )
    }

    @Test
    fun appendsTabAndThemeWhenGiven() {
        assertEquals(
            "https://plaud.example.com?embedded=1&tab=automations&theme=light",
            WebDashboardActivity.embeddedUrl("https://plaud.example.com", WebDashboardActivity.TAB_AUTOMATIONS, DashboardTheme.LIGHT),
        )
        assertEquals(
            "https://plaud.example.com?embedded=1&theme=dark",
            WebDashboardActivity.embeddedUrl("https://plaud.example.com", null, DashboardTheme.DARK),
        )
    }

    @Test
    fun dashboardThemeFollowsTheAppsAppearanceSetting() {
        assertEquals(DashboardTheme.LIGHT, DashboardTheme.forNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, appFollowsSystem = false))
        assertEquals(DashboardTheme.SYSTEM, DashboardTheme.forNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, appFollowsSystem = true))
        assertEquals(DashboardTheme.DARK, DashboardTheme.forNightMode(AppCompatDelegate.MODE_NIGHT_YES, appFollowsSystem = false))
        assertEquals(DashboardTheme.LIGHT, DashboardTheme.forNightMode(AppCompatDelegate.MODE_NIGHT_NO, appFollowsSystem = true))
        // The app is DayNight and defaults to following the system, so the page does too
        assertEquals(DashboardTheme.SYSTEM, DashboardTheme.current())
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        try {
            assertEquals(DashboardTheme.DARK, DashboardTheme.current())
        } finally {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }
}
