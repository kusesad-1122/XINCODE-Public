package com.xincode.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * App Widget that shows current Agent status and provides a quick-send shortcut.
 * Updated by XincodeApplication whenever agent state changes.
 */
class XincodeWidgetReceiver : AppWidgetProvider() {

    companion object {
        private var lastStatus: String = "空闲"

        /** Called by XincodeApplication to push status updates to all widgets. */
        fun updateAll(context: Context, status: String) {
            lastStatus = status
            val intent = Intent(context, XincodeWidgetReceiver::class.java)
            intent.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, XincodeWidgetReceiver::class.java))
            if (ids.isNotEmpty()) {
                intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                context.sendBroadcast(intent)
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId, lastStatus)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                ?: manager.getAppWidgetIds(ComponentName(context, XincodeWidgetReceiver::class.java))
            for (id in ids) {
                updateWidget(context, manager, id, lastStatus)
            }
        }
    }

    private fun updateWidget(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        status: String
    ) {
        val views = RemoteViews(context.packageName, R.layout.xincode_widget)

        // Status text
        views.setTextViewText(R.id.widget_status, status)

        // "XINCODE" title
        views.setTextViewText(R.id.widget_title, "XINCODE")

        // Click to open app
        val launchIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

        manager.updateAppWidget(widgetId, views)
    }
}