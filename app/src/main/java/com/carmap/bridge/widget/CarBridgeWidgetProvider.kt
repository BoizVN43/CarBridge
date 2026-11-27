package com.carmap.bridge.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.carmap.bridge.MainActivity
import com.carmap.bridge.R
import com.carmap.bridge.data.BridgeRepository
import com.carmap.bridge.util.CarBridgeNotifier

class CarBridgeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        BridgeRepository.init(context)
        val firstSaved = BridgeRepository.state.value.savedPlaces.firstOrNull()

        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_carbridge)

            val voiceIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(CarBridgeNotifier.EXTRA_START_VOICE, true)
            }
            views.setOnClickPendingIntent(
                R.id.btn_widget_voice,
                PendingIntent.getActivity(
                    context,
                    101,
                    voiceIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            val clipIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(CarBridgeNotifier.EXTRA_SEND_CLIPBOARD, true)
            }
            views.setOnClickPendingIntent(
                R.id.btn_widget_clip,
                PendingIntent.getActivity(
                    context,
                    102,
                    clipIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            val homeIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (firstSaved != null) {
                    putExtra(EXTRA_SEND_SAVED_LINK, firstSaved.rawLink)
                }
            }
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            views.setTextViewText(
                R.id.btn_widget_home,
                firstSaved?.label?.take(10) ?: "Mở App"
            )
            val homePi = PendingIntent.getActivity(
                context,
                103,
                homeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val logoPi = PendingIntent.getActivity(
                context,
                104,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_widget_home, homePi)
            views.setOnClickPendingIntent(R.id.widget_logo, logoPi)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    companion object {
        const val EXTRA_SEND_SAVED_LINK = "extra_send_saved_link"

        fun requestUpdate(context: Context) {
            runCatching {
                val app = context.applicationContext
                val mgr = AppWidgetManager.getInstance(app)
                val ids = mgr.getAppWidgetIds(ComponentName(app, CarBridgeWidgetProvider::class.java))
                if (ids.isEmpty()) return
                val intent = Intent(app, CarBridgeWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                app.sendBroadcast(intent)
            }
        }
    }
}
