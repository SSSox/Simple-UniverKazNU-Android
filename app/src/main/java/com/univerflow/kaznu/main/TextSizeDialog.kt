package com.univerflow.kaznu.main

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.univerflow.kaznu.R
import com.univerflow.kaznu.domain.TextZoom
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider

/** The text size of the website and the viewers: a slider applied while it moves, with Reset to the default. */
object TextSizeDialog {
    /** [labels] gives the app-language strings; the dialog itself needs the activity's window. */
    fun show(activity: Activity, labels: Context, current: Int, onChange: (Int) -> Unit): Dialog {
        val density = activity.resources.displayMetrics.density
        val value = TextView(activity).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            gravity = Gravity.CENTER
        }
        val slider = Slider(activity).apply {
            valueFrom = TextZoom.MIN.toFloat(); valueTo = TextZoom.MAX.toFloat(); stepSize = 10f
            this.value = current.toFloat()
            setLabelFormatter { labels.getString(R.string.percent_value, it.toInt()) }
            contentDescription = labels.getString(R.string.text_size)
        }
        value.text = labels.getString(R.string.percent_value, slider.value.toInt())
        slider.addOnChangeListener { _, position, fromUser ->
            value.text = labels.getString(R.string.percent_value, position.toInt())
            if (fromUser) onChange(position.toInt())
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val side = (24 * density).toInt()
            setPadding(side, (8 * density).toInt(), side, 0)
            addView(value); addView(slider)
        }
        return MaterialAlertDialogBuilder(activity).setTitle(labels.getString(R.string.text_size)).setIcon(R.drawable.ic_text_size)
            .setView(content).setPositiveButton(labels.getString(R.string.done), null)
            .setNeutralButton(labels.getString(R.string.reset)) { _, _ -> onChange(TextZoom.DEFAULT) }.show()
    }
}
