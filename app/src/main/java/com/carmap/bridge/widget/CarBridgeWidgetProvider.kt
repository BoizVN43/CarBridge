package com.carmap.bridge.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
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

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (firstSaved != null) {
                    putExtra(EXTRA_SEND_SAVED_LINK, firstSaved.rawLink)
                }
            }
            views.setTextViewText(
                R.id.btn_widget_home,
                firstSaved?.label?.take(10) ?: "Mở App"
            )
            val openPi = PendingIntent.getActivity(
                context,
                103,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_widget_home, openPi)
            views.setOnClickPendingIntent(R.id.widget_logo, openPi)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    companion object {
        const val EXTRA_SEND_SAVED_LINK = "extra_send_saved_link"
    }
}
