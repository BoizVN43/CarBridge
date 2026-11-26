package com.carmap.bridge.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

object OemAutoStartHelper {
    private val oemIntents = listOf(
        // Car Android Box / Headunit ROMs (FYT / SYU / Topway TS10 / Carlinkit / TBox / XYAuto)
        ComponentName("com.syu.settings", "com.syu.settings.AutoStartActivity"),
        ComponentName("com.ts.MainUI", "com.ts.set.SetBootAppActivity"),
        ComponentName("com.xyauto.Settings", "com.xyauto.Settings.AutoStartActivity"),
        ComponentName("com.carlinkit.autokit", "com.carlinkit.autokit.AutoStartActivity"),
        ComponentName("com.tw.settings", "com.tw.settings.AutoStartActivity"),
        // Phone / Tablet OEM ROMs
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
        ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity")
    )

    fun openAutoStartSettings(context: Context) {
        for (cn in oemIntents) {
            val intent = Intent().apply {
                component = cn
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (context.packageManager.resolveActivity(intent, 0) != null) {
                val ok = runCatching { context.startActivity(intent); true }.getOrDefault(false)
                if (ok) {
                    Toast.makeText(
                        context,
                        "Bật quyền Tự khởi động / Chạy nền cho CarBridge",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
            }
        }
        runCatching {
            val fallback = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(fallback)
            Toast.makeText(
                context,
                "Vào mục Pin / Sử dụng pin → chọn Không hạn chế để Box tự chạy ngầm",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
