package io.github.adamrb.transom.common

import androidx.appcompat.app.AppCompatDelegate

/**
 * The Appearance setting: follow the system, or force the light or dark palette. Stored as a
 * short string in RecordingStore; applied through [AppCompatDelegate.setDefaultNightMode] at
 * process start and again the moment the user changes it (which recreates the visible activity).
 */
enum class Appearance(val storageKey: String, val nightMode: Int) {
    SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
    DARK("dark", AppCompatDelegate.MODE_NIGHT_YES);

    fun apply() = AppCompatDelegate.setDefaultNightMode(nightMode)

    companion object {
        /** The stored value, or [SYSTEM] for anything unknown (including nothing stored yet). */
        fun fromStorage(key: String?): Appearance = values().firstOrNull { it.storageKey == key } ?: SYSTEM
    }
}
