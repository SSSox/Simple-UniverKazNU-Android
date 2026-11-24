package com.univerflow.kaznu.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.univerflow.kaznu.R
import com.univerflow.kaznu.databinding.ItemDownloadBinding
import com.univerflow.kaznu.databinding.ItemDownloadHeadingBinding
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.DownloadRecord
import com.univerflow.kaznu.domain.DownloadState
import com.univerflow.kaznu.domain.FileKind
import com.univerflow.kaznu.domain.FileKinds
import com.univerflow.kaznu.domain.TextSearch
import com.univerflow.kaznu.download.DownloadService
import com.google.android.material.shape.ShapeAppearanceModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A line of the Downloads list: a period heading ("Today") or a file card. */
sealed interface DownloadRow {
    data class Heading(val title: String) : DownloadRow
    /** [first] and [last] place the file in its group's sheet; [snippet] is what a search found inside the file. */
    data class File(val record: DownloadRecord, val first: Boolean, val last: Boolean, val snippet: TextSearch.Snippet? = null) : DownloadRow
}

/**
 * The cards of the Downloads list: type badge, name, course, size or progress and date, the words a text search found,
 * and the card's action (cancel a running download, or the file's menu). What a tap does stays with the screen.
 */
class DownloadsAdapter(
    private val context: Context,
    private val language: AppLanguage,
    /** The app-language resources; built once by the screen, because every bound card asks for strings. */
    private val labels: Context,
    private val host: Host,
) : ListAdapter<DownloadRow, RecyclerView.ViewHolder>(DIFF) {
    interface Host {
        /** The file is no longer where it was saved (moved or deleted in another app). */
        fun missing(id: String): Boolean
        fun open(record: DownloadRecord)
        fun actions(anchor: View, record: DownloadRecord)
        fun cancel(record: DownloadRecord)
    }

    private val density = context.resources.displayMetrics.density
    /** A card's corners by its place in its group's sheet: bit 1 rounds the top (first card), bit 2 the bottom (last). */
    private val cardShapes = Array(4) { place ->
        val radius = 12 * density
        val top = if (place and 1 != 0) radius else 0f
        val bottom = if (place and 2 != 0) radius else 0f
        ShapeAppearanceModel.builder().setTopLeftCornerSize(top).setTopRightCornerSize(top)
            .setBottomLeftCornerSize(bottom).setBottomRightCornerSize(bottom).build()
    }
    /** `SimpleDateFormat` is not cheap to build, and a list of a thousand cards formats on every scroll. */
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.forLanguageTag(language.localeCode))

    private class HeadingHolder(val item: ItemDownloadHeadingBinding) : RecyclerView.ViewHolder(item.root) {
        init { ViewCompat.setAccessibilityHeading(item.root, true) }
    }

    private class Holder(val item: ItemDownloadBinding) : RecyclerView.ViewHolder(item.root) {
        /** The record this card shows: only its own progress moves, a recycled card takes its new value at once. */
        var shown: String? = null
    }

    override fun getItemViewType(position: Int) = if (getItem(position) is DownloadRow.Heading) HEADING else FILE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = LayoutInflater.from(parent.context).let { inflater ->
        if (viewType == HEADING) HeadingHolder(ItemDownloadHeadingBinding.inflate(inflater, parent, false))
        else Holder(ItemDownloadBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is DownloadRow.Heading -> (holder as HeadingHolder).item.downloadHeading.text = row.title
            is DownloadRow.File -> bind(holder as Holder, row)
        }
    }

    private fun bind(holder: Holder, row: DownloadRow.File) {
        val record = row.record
        val view = holder.item
        // A running download rebinds its card every 250 ms: the same corners are not rebuilt and re-applied each time.
        val shape = cardShapes[(if (row.first) 1 else 0) or (if (row.last) 2 else 0)]
        if (view.downloadCard.shapeAppearanceModel !== shape) view.downloadCard.shapeAppearanceModel = shape
        view.downloadLine.isVisible = !row.last
        (view.root.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = if (row.last) (4 * density).toInt() else 0
        val kind = record.kind
        val plateColor = context.getColor(kindColor(kind))
        val textColor = when (kind) {
            FileKind.PDF -> context.getColor(R.color.kind_pdf_text)
            FileKind.POWERPOINT -> context.getColor(R.color.kind_powerpoint_text)
            else -> plateColor
        }
        view.downloadBadge.text = FileKinds.badge(kind)
        view.downloadBadge.setTextColor(textColor)
        view.downloadBadge.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(plateColor, 0x24))
        view.downloadName.text = FileKinds.title(record.fileName).ifBlank { "…" }
        val meta = status(record)
        view.downloadMeta.text = meta
        view.downloadSource.text = record.source
        view.downloadSource.isVisible = record.source.isNotBlank()
        val snippet = row.snippet
        view.downloadSnippet.isVisible = snippet != null
        if (snippet != null) view.downloadSnippet.text = SpannableString(snippet.text).apply {
            setSpan(StyleSpan(Typeface.BOLD), snippet.start, snippet.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(BackgroundColorSpan(ColorUtils.setAlphaComponent(context.getColor(R.color.kind_word), 0x33)), snippet.start, snippet.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        view.downloadProgress.isVisible = record.state == DownloadState.RUNNING
        record.progress.let { progress ->
            view.downloadProgress.isIndeterminate = progress == null
            if (progress != null) view.downloadProgress.setProgressCompat(progress, holder.shown == record.id)
        }
        holder.shown = record.id
        view.downloadCard.contentDescription = listOfNotNull(view.downloadName.text.toString(), record.source.ifBlank { null }, meta, snippet?.text)
            .joinToString(". ")
        view.downloadCard.setOnClickListener { host.open(record) }
        if (record.state == DownloadState.RUNNING) {
            view.downloadAction.setIconResource(R.drawable.ic_close)
            view.downloadAction.contentDescription = labels.getString(R.string.cancel_download)
            view.downloadAction.setOnClickListener { host.cancel(record) }
        } else {
            view.downloadAction.setIconResource(R.drawable.ic_more)
            view.downloadAction.contentDescription = labels.getString(R.string.file_actions)
            view.downloadAction.setOnClickListener { host.actions(it, record) }
        }
    }

    /** "45% · 1.2 MB of 3 MB", the size, or why it stopped, then the date. */
    private fun status(record: DownloadRecord): String {
        val state = when (record.state) {
            DownloadState.RUNNING -> {
                val received = FileKinds.size(record.received, language)
                record.progress?.let { labels.getString(R.string.state_running_progress, it, received, FileKinds.size(record.total, language)) }
                    ?: labels.getString(R.string.state_running_bytes, received)
            }
            DownloadState.COMPLETED -> if (host.missing(record.id)) labels.getString(R.string.state_missing)
                else FileKinds.size(record.total.takeIf { it >= 0 } ?: record.received, language)
            DownloadState.FAILED -> labels.getString(R.string.state_failed) + ". " + labels.getString(DownloadService.failureMessage(record.failure))
            DownloadState.CANCELED -> labels.getString(R.string.state_canceled)
        }
        return listOf(state, dateFormat.format(Date(record.displayTime))).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private companion object {
        const val HEADING = 0
        const val FILE = 1

        fun kindColor(kind: FileKind) = when (kind) {
            FileKind.PDF -> R.color.kind_pdf
            FileKind.WORD -> R.color.kind_word
            FileKind.EXCEL -> R.color.kind_excel
            FileKind.POWERPOINT -> R.color.kind_powerpoint
            FileKind.IMAGE -> R.color.kind_image
            else -> R.color.kind_other
        }

        val DIFF = object : DiffUtil.ItemCallback<DownloadRow>() {
            override fun areItemsTheSame(old: DownloadRow, new: DownloadRow) = when {
                old is DownloadRow.File && new is DownloadRow.File -> old.record.id == new.record.id
                else -> old == new
            }
            override fun areContentsTheSame(old: DownloadRow, new: DownloadRow) = old == new
        }
    }
}
