package io.github.adamrb.transom.ui.common

import android.content.Context
import android.graphics.Color
import android.view.View
import androidx.annotation.AttrRes
import com.google.android.material.color.MaterialColors

/**
 * The one way code reads a colour: through the theme, so the value follows the light or dark
 * palette the way `?attr/...` does in layouts. Never resolve a `R.color.*` for something drawn on
 * screen; the palettes live in values/colors.xml and values-night/colors.xml and only the theme
 * knows which one is active.
 */
fun Context.themeColor(@AttrRes attr: Int): Int = MaterialColors.getColor(this, attr, Color.MAGENTA)

fun View.themeColor(@AttrRes attr: Int): Int = context.themeColor(attr)
