package org.plaudbridge.app.ui.library

import androidx.appcompat.app.AppCompatDelegate

/**
 * The `theme` value handed to the embedded web dashboard so it renders in step with the app
 * ("light", "dark" or "system").
 *
 * The app's own theme is light-only today ([org.plaudbridge.app.R.style.Theme_PlaudBridge] is not
 * a DayNight theme), so the dashboard must not follow the system on its own: the page would go
 * dark inside a light app. Once the app gets a DayNight theme, pass [appFollowsSystem] = true and
 * the system setting flows through.
 */
object DashboardTheme {

    const val LIGHT = "light"
    const val DARK = "dark"
    const val SYSTEM = "system"

    fun current(appFollowsSystem: Boolean = false): String =
        forNightMode(AppCompatDelegate.getDefaultNightMode(), appFollowsSystem)

    fun forNightMode(nightMode: Int, appFollowsSystem: Boolean): String = when (nightMode) {
        AppCompatDelegate.MODE_NIGHT_YES -> DARK
        AppCompatDelegate.MODE_NIGHT_NO -> LIGHT
        else -> if (appFollowsSystem) SYSTEM else LIGHT
    }
}
