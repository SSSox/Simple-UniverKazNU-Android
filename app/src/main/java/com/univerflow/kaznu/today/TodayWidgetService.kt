package com.univerflow.kaznu.today

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.net.toUri
import com.univerflow.kaznu.data.AppPreferences
import com.univerflow.kaznu.data.LockVault
import com.univerflow.kaznu.data.TimetableStore
import com.univerflow.kaznu.domain.TodayPlan
import java.util.TimeZone

/**
 * The rows of [TodayWidget]'s scrolling list, from the saved week only. Bound by the launcher (BIND_REMOTEVIEWS, not
 * exported); [RemoteViewsFactory.onDataSetChanged] runs off the main thread, so the file is read there.
 */
class TodayWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Rows(applicationContext)

    private class Rows(private val context: Context) : RemoteViewsFactory {
        private var rows: List<RemoteViews> = emptyList()

        override fun onCreate() {}

        override fun onDataSetChanged() {
            val now = System.currentTimeMillis()
            val zone = TimeZone.getDefault()
            val list = TimetableStore.read(context)?.let { TodayPlan.list(it, now, zone) }
            val hidden = runCatching { LockVault(context).exists() }.getOrDefault(true)
            rows = TodayWidget.rows(context, list, hidden, AppPreferences(context).language, zone)
        }

        override fun onDestroy() { rows = emptyList() }

        override fun getCount() = rows.size

        override fun getViewAt(position: Int): RemoteViews? = rows.getOrNull(position)

        override fun getLoadingView(): RemoteViews? = null

        /** widget_lesson and widget_day. */
        override fun getViewTypeCount() = 2

        override fun getItemId(position: Int) = position.toLong()

        override fun hasStableIds() = false
    }

    companion object {
        /** One adapter per widget: the data URI keeps the launcher from sharing a factory between two placed widgets. */
        fun intent(context: Context, id: Int): Intent = Intent(context, TodayWidgetService::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .setData("univer-widget://today/$id".toUri())
    }
}
