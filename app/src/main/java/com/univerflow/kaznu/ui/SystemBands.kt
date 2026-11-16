package com.univerflow.kaznu.ui

import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import androidx.core.graphics.drawable.toDrawable
import com.univerflow.kaznu.R

/**
 * The strips behind the system bars must not read as bars of their own: the one under the status bar continues the app
 * bar, and everything below it, the navigation bar's strip included, continues the page.
 */
fun View.paintSystemBands(statusBarHeight: Int) {
    // Insets arrive again with every keyboard move: the same bands are not rebuilt and redrawn each time.
    val painted = background as? LayerDrawable
    if (painted != null && painted.numberOfLayers == 2 && painted.getLayerHeight(1) == statusBarHeight) return
    background = LayerDrawable(arrayOf(context.getColor(R.color.md_background).toDrawable(),
        context.getColor(R.color.app_bar).toDrawable())).apply {
        setLayerGravity(1, Gravity.TOP or Gravity.FILL_HORIZONTAL)
        setLayerHeight(1, statusBarHeight)
    }
}
