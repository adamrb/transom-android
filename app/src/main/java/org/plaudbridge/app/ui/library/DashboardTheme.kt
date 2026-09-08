package org.plaudbridge.app.ui.library

import androidx.appcompat.app.AppCompatDelegate

/**
 * The `theme` value handed to the embedded web dashboard so it renders in step with the app
 * ("light", "dark" or "system", CONTRACTS §8).
 *
 * The app's theme is DayNight and the Appearance setting drives [AppCompatDelegate]'s default
 * night mode: a forced Light or Dark is passed through as such, and "System" is passed as
 * `system` so the page follows the OS exactly as the app does.
 */
object DashboardTheme {

    const val LIGHT = "light"
    const val DARK = "dark"
    const val SYSTEM = "system"

    fun current(appFollowsSystem: Boolean = true): String =
        forNightMode(AppCompatDelegate.getDefaultNightMode(), appFollowsSystem)

    fun forNightMode(nightMode: Int, appFollowsSystem: Boolean): String = when (nightMode) {
        AppCompatDelegate.MODE_NIGHT_YES -> DARK
        AppCompatDelegate.MODE_NIGHT_NO -> LIGHT
        else -> if (appFollowsSystem) SYSTEM else LIGHT
    }
}
