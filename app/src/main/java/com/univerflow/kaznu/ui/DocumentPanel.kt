package com.univerflow.kaznu.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import com.univerflow.kaznu.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * The frame of the document and service window, above the page: its bar (back, title, reading mode, print), a hairline,
 * the loading line and the error panel with Retry. The page itself goes in with [addStage]; what a tap does stays with
 * [DocumentViewer].
 */
class DocumentPanel(context: Context, onBack: () -> Unit, onMode: () -> Unit, onPrint: () -> Unit, onRetry: () -> Unit) {
    val root: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(context.getColor(R.color.md_background))
        isClickable = true // The source page below must not receive taps while a document is open.
    }
    private val back: MaterialButton
    private val title: TextView
    private val subtitle: TextView
    private val mode: MaterialButton
    private val print: MaterialButton
    private val progress: LinearProgressIndicator
    private val errorPanel: LinearLayout
    private val errorMessage: TextView
    private val retry: MaterialButton

    init {
        val inflater = LayoutInflater.from(context)
        val bar = inflater.inflate(R.layout.view_document_bar, root, false)
        back = bar.findViewById<MaterialButton>(R.id.document_back).apply { setOnClickListener { onBack() } }
        title = bar.findViewById(R.id.document_title)
        subtitle = bar.findViewById(R.id.document_subtitle)
        mode = bar.findViewById<MaterialButton>(R.id.document_mode).apply { setOnClickListener { onMode() } }
        print = bar.findViewById<MaterialButton>(R.id.document_print).apply { setOnClickListener { onPrint() } }
        root.addView(bar)
        val hairline = context.resources.displayMetrics.density.toInt().coerceAtLeast(1)
        root.addView(View(context).apply { setBackgroundColor(context.getColor(R.color.app_bar_line)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hairline))
        progress = LinearProgressIndicator(context).apply { isIndeterminate = false; trackThickness = (3 * context.resources.displayMetrics.density).toInt() }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        errorPanel = inflater.inflate(R.layout.view_document_error, root, false) as LinearLayout
        errorMessage = errorPanel.findViewById(R.id.document_error_message)
        retry = errorPanel.findViewById<MaterialButton>(R.id.document_retry).apply { setOnClickListener { onRetry() } }
        root.addView(errorPanel)
    }

    /** The window's title as shown, the name a printed copy gets. */
    val titleText: String get() = title.text.toString()

    /** The page and its placeholder, filling the rest of the window. */
    fun addStage(stage: View) { root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)) }

    fun loading(shown: Boolean) { progress.visibility = if (shown) View.VISIBLE else View.GONE }

    /** WebView's own progress; the line goes once the page is complete or has failed. */
    fun progress(percent: Int, failed: Boolean) {
        progress.setProgressCompat(percent, true)
        loading(!failed && percent < 100)
    }

    fun showError(message: CharSequence) {
        loading(false)
        errorPanel.visibility = View.VISIBLE
        errorMessage.text = message
    }

    fun hideError() { errorPanel.visibility = View.GONE }

    /**
     * Labels in the app language. A service window (Moodle) shows its host as the subtitle and has neither reading
     * mode nor print; [readable] names the mode the button switches to.
     */
    fun label(labels: Context, titleId: Int, subtitleText: String?, service: Boolean, readable: Boolean, error: CharSequence) {
        title.text = labels.getString(titleId)
        subtitle.text = subtitleText
        print.visibility = if (service) View.GONE else View.VISIBLE
        mode.visibility = if (service) View.GONE else View.VISIBLE
        tooltip(back, labels.getString(if (service) R.string.service_back else R.string.document_back))
        tooltip(print, labels.getString(R.string.print))
        mode.text = labels.getString(if (readable) R.string.document_original else R.string.document_read)
        mode.setIconResource(if (readable) R.drawable.ic_file else R.drawable.ic_reader)
        retry.text = labels.getString(R.string.retry)
        errorMessage.text = error
    }

    private fun tooltip(button: View, text: String) {
        button.contentDescription = text
        TooltipCompat.setTooltipText(button, text)
    }
}
