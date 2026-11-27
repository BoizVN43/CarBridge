package com.carmap.bridge.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.carmap.bridge.MainActivity
import com.carmap.bridge.R
import com.carmap.bridge.model.DiscoveredBox

object CarBridgeNotifier {

    const val EXTRA_OPEN_OTA = "extra_open_ota"
    const val EXTRA_SEND_CLIPBOARD = "extra_send_clipboard"
    const val EXTRA_START_VOICE = "extra_start_voice"

    private const val CHANNEL_OTA = "carbridge_ota_updates"
    private const val CHANNEL_CONNECTION = "carbridge_box_connection"
    private const val CHANNEL_ALERT = "carbridge_send_alerts"

    private const val NOTIF_ID_OTA = 5001
    private const val NOTIF_ID_CONNECTED = 5002
    private const val NOTIF_ID_SEND_FAILED = 5003

    private fun ensureChannels(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        val otaChannel = NotificationChannel(
            CHANNEL_OTA,
            "Cập nhật ứng dụng CarBridge",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Thông báo ngay khi có bản cập nhật CarBridge mới trên GitHub Releases"
        }
        val connChannel = NotificationChannel(
            CHANNEL_CONNECTION,
            "Trạng thái kết nối Android Box",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Thông báo khi điện thoại kết nối với Android Box trên xe"
        }
        val alertChannel = NotificationChannel(
            CHANNEL_ALERT,
            "Cảnh báo gửi sang Box",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Thông báo popup khi gửi bản đồ nhưng chưa kết nối được với Android Box"
        }
        mgr.createNotificationChannel(otaChannel)
        mgr.createNotificationChannel(connChannel)
        mgr.createNotificationChannel(alertChannel)
    }

    private fun canPostNotifications(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
    }

    fun notifyOtaUpdateAvailable(
        context: Context,
        versionName: String,
        sizeLabel: String
    ) {
        if (!canPostNotifications(context)) return
        ensureChannels(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_OTA, true)
        }
        val pi = PendingIntent.getActivity(
            context,
            NOTIF_ID_OTA,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sizePart = if (sizeLabel.isNotBlank()) " ($sizeLabel)" else ""
        val notif = NotificationCompat.Builder(context, CHANNEL_OTA)
            .setSmallIcon(R.drawable.ic_car_map)
            .setContentTitle("CarBridge v$versionName đã sẵn sàng")
            .setContentText("Có bản cập nhật mới$sizePart. Nhấn để tải và cài đè ngay.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID_OTA, notif) }
    }

    fun notifyBoxConnected(context: Context, box: DiscoveredBox) {
        if (!canPostNotifications(context)) return
        ensureChannels(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(
            context,
            NOTIF_ID_CONNECTED,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val clipIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SEND_CLIPBOARD, true)
        }
        val clipPi = PendingIntent.getActivity(
            context,
            NOTIF_ID_CONNECTED + 1,
            clipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val voiceIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_START_VOICE, true)
        }
        val voicePi = PendingIntent.getActivity(
            context,
            NOTIF_ID_CONNECTED + 2,
            voiceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_car_map)
            .setContentTitle("Đã kết nối với ${box.name}")
            .setContentText("${box.channel.label} (${box.host}) · Sẵn sàng gửi bản đồ & Clipboard")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .addAction(0, "Gửi Clipboard", clipPi)
            .addAction(0, "Giọng nói", voicePi)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID_CONNECTED, notif) }
    }

    fun notifyBoxNotConnected(
        context: Context,
        destinationTitle: String,
        reason: String
    ) {
        if (!canPostNotifications(context)) return
        ensureChannels(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(
            context,
            NOTIF_ID_SEND_FAILED,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val titleSafe = destinationTitle.ifBlank { "Địa điểm đã chọn" }
        val message = "Không gửi được \"$titleSafe\". $reason"
        val notif = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_car_map)
            .setContentTitle("Chưa kết nối được với Android Box")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID_SEND_FAILED, notif) }
    }

    fun cancelSendFailedNotification(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID_SEND_FAILED) }
    }

    fun cancelBoxConnectedNotification(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID_CONNECTED) }
    }
}
