package com.carmap.bridge.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.carmap.bridge.launcher.LaunchSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

object RootHelper {

    @Volatile
    var isRootGranted: Boolean = false
        private set

    data class WindowSnapshot(
        val isInMultiWindow: Boolean,
        val visiblePackages: Set<String>,
        val topPackage: String?
    )

    private fun shQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun execSu(command: String, timeoutMs: Long = 6_000L): Pair<Int, String> {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val outBuilder = StringBuilder()
            val readerThread = Thread {
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (outBuilder.length < 32_768) {
                                outBuilder.append(line).append('\n')
                            }
                        }
                    }
                }
            }.apply { start() }

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                readerThread.join(400L)
                return -1 to "Root command timeout"
            }
            readerThread.join(600L)
            process.exitValue() to outBuilder.toString().trim()
        } catch (e: Exception) {
            -1 to (e.localizedMessage ?: "Không gọi được su")
        }
    }

    suspend fun checkRootAccess(): Boolean = withContext(Dispatchers.IO) {
        val (code, out) = execSu("id", 3_500L)
        val granted = code == 0 && out.contains("uid=0")
        isRootGranted = granted
        granted
    }

    suspend fun applySystemPrivileges(packageName: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!isRootGranted && !checkRootAccess()) {
            return@withContext false to "Box chưa cấp quyền Root (su)"
        }
        val pkg = packageName.trim()
        val cmd = buildString {
            append("dumpsys deviceidle whitelist +").append(pkg).append("; ")
            append("appops set ").append(pkg).append(" SYSTEM_ALERT_WINDOW allow; ")
            append("appops set ").append(pkg).append(" RUN_IN_BACKGROUND allow; ")
            append("appops set ").append(pkg).append(" RUN_ANY_IN_BACKGROUND allow; ")
            append("appops set ").append(pkg).append(" READ_CLIPBOARD_IN_BACKGROUND allow 2>/dev/null || true; ")
            append("pm grant ").append(pkg).append(" android.permission.POST_NOTIFICATIONS 2>/dev/null || true")
        }
        val (code, out) = execSu(cmd, 8_000L)
        if (code == 0 || out.contains("Added") || out.isBlank()) {
            true to "Đã cấp toàn bộ quyền nền, Overlay & Clipboard qua Root"
        } else {
            false to "Lỗi áp dụng quyền Root: ${out.take(100)}"
        }
    }

    suspend fun ensureRadiosEnabled(): Boolean = withContext(Dispatchers.IO) {
        if (!isRootGranted && !checkRootAccess()) return@withContext false
        val (code, _) = execSu("svc wifi enable; svc bluetooth enable", 5_000L)
        code == 0
    }

    suspend fun silentInstallApk(apkFile: File): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!apkFile.exists()) return@withContext false to "Không tìm thấy tệp APK"
        if (!isRootGranted && !checkRootAccess()) return@withContext false to "Chưa có quyền Root"

        val tmpPath = "/data/local/tmp/carbridge_silent_install.apk"
        try {
            val installCmd = "cp ${shQuote(apkFile.absolutePath)} $tmpPath && chmod 644 $tmpPath && pm install -r -d $tmpPath"
            val (code, out) = execSu(installCmd, 30_000L)
            if (code == 0 && out.contains("Success", ignoreCase = true)) {
                true to "Đã cài ngầm ${apkFile.name}"
            } else {
                false to (out.lines().lastOrNull()?.take(120) ?: "Lỗi pm install ($code)")
            }
        } finally {
            execSu("rm -f $tmpPath", 2_000L)
        }
    }

    suspend fun inspectWindowState(): WindowSnapshot = withContext(Dispatchers.IO) {
        val (_, containers) = execSu("dumpsys activity containers", 3_000L)
        val (_, activities) = execSu("dumpsys activity activities", 3_000L)

        val splitIndicators = listOf(
            "windowingMode=split-screen",
            "windowingMode=multi-window",
            "mWindowingMode=multi-window",
            "windowingMode=3",
            "windowingMode=4",
            "windowingMode=6",
            "split-screen-primary",
            "split-screen-secondary"
        )
        val combined = "$containers\n$activities"
        val isMulti = splitIndicators.any { combined.contains(it, ignoreCase = true) }

        val pkgRegex = Regex("""([a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+)/""")
        val visiblePkgs = linkedSetOf<String>()
        var topPkg: String? = null

        activities.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.contains("topResumedActivity=", ignoreCase = true) ||
                trimmed.contains("mResumedActivity:", ignoreCase = true) ||
                trimmed.contains("ResumedActivity:", ignoreCase = true)
            ) {
                val pkg = pkgRegex.find(trimmed)?.groupValues?.getOrNull(1)
                if (!pkg.isNullOrBlank()) {
                    if (topPkg == null) topPkg = pkg
                    visiblePkgs.add(pkg)
                }
            }
        }

        WindowSnapshot(
            isInMultiWindow = isMulti || visiblePkgs.size >= 2,
            visiblePackages = visiblePkgs,
            topPackage = topPkg
        )
    }

    suspend fun launchMapSmartRoot(
        context: Context,
        spec: LaunchSpec,
        autoSplitEnabled: Boolean
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!isRootGranted && !checkRootAccess()) {
            return@withContext false to "Chưa có quyền Root"
        }
        val pkg = spec.targetPackage ?: return@withContext false to "Không xác định gói bản đồ"
        val win = inspectWindowState()
        val quotedUri = shQuote(spec.intentUri)
        val quotedPkg = shQuote(pkg)

        // Case 1: Box is already in Split-Screen mode -> NEVER collapse split-screen!
        if (win.isInMultiWindow) {
            return@withContext if (pkg in win.visiblePackages) {
                // Keep existing split window; deliver NEW_TASK | SINGLE_TOP intent directly into that split pane
                val intentOk = runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(spec.intentUri)).apply {
                        setPackage(pkg)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    context.startActivity(intent)
                    true
                }.getOrDefault(false)
                if (intentOk) {
                    true to "Đã cập nhật dẫn đường trong màn hình chia đôi"
                } else {
                    val (c, o) = execSu("am start -f 0x30000000 -a android.intent.action.VIEW -d $quotedUri -p $quotedPkg", 5_000L)
                    (c == 0 && !o.contains("Error:", ignoreCase = true)) to "Đã cập nhật dẫn đường trong màn hình chia đôi"
                }
            } else {
                // Launch another map app adjacent in multi-window
                val (c, o) = execSu("am start -f 0x30001000 -a android.intent.action.VIEW -d $quotedUri -p $quotedPkg", 5_000L)
                if (c == 0 && !o.contains("Error:", ignoreCase = true)) {
                    true to "Đã mở bản đồ vào nửa màn hình chia đôi"
                } else {
                    val (c2, o2) = execSu("am start -a android.intent.action.VIEW -d $quotedUri -p $quotedPkg --activity-clear-top", 5_000L)
                    (c2 == 0 && !o2.contains("Error:", ignoreCase = true)) to "Đã mở bản đồ qua Root"
                }
            }
        }

        // Case 2: Fullscreen running another media/app (not launcher, not CarBridge, not target map) + Auto Split ON
        val top = win.topPackage
        val isOtherAppFullscreen = autoSplitEnabled &&
            !top.isNullOrBlank() &&
            top != context.packageName &&
            top != pkg &&
            !top.contains("launcher", ignoreCase = true) &&
            !top.contains("home", ignoreCase = true) &&
            top != "com.android.systemui"

        if (isOtherAppFullscreen) {
            val splitCmd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                "am start --windowingMode 6 -f 0x30001000 -a android.intent.action.VIEW -d $quotedUri -p $quotedPkg"
            } else {
                "am start --windowingMode 4 -a android.intent.action.VIEW -d $quotedUri -p $quotedPkg"
            }
            val (splitCode, splitOut) = execSu(splitCmd, 5_000L)
            if (splitCode == 0 &&
                !splitOut.contains("Error", ignoreCase = true) &&
                !splitOut.contains("Exception", ignoreCase = true)
            ) {
                return@withContext true to "Đã tự chia đôi màn hình & mở dẫn đường"
            }
        }

        // Case 3: Standard forced Root launch
        val (code, out) = execSu("am start -a android.intent.action.VIEW -d $quotedUri -p $quotedPkg --activity-clear-top", 5_000L)
        if (code == 0 && !out.contains("Error:", ignoreCase = true)) {
            true to "Đã mở bản đồ bằng quyền Root"
        } else {
            false to out.take(120)
        }
    }

    suspend fun executeBoxSystemCommand(command: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!isRootGranted && !checkRootAccess()) {
            return@withContext false to "Box chưa cấp quyền Root"
        }
        when (command.uppercase()) {
            "FORCE_STOP_MAPS" -> {
                val (code, _) = execSu(
                    "am force-stop com.google.android.apps.maps; " +
                        "am force-stop com.waze; " +
                        "am force-stop vn.vietmap.live; " +
                        "am force-stop vn.vietmap.s2",
                    6_000L
                )
                (code == 0) to "Đã dọn & đóng các app bản đồ bị treo"
            }
            "KEY_HOME" -> {
                val (code, _) = execSu("input keyevent 3", 4_000L)
                (code == 0) to "Đã bấm nút Home trên Box"
            }
            "KEY_BACK" -> {
                val (code, _) = execSu("input keyevent 4", 4_000L)
                (code == 0) to "Đã bấm nút Back trên Box"
            }
            "REBOOT" -> {
                execSu("(sleep 1; reboot) >/dev/null 2>&1 &", 2_500L)
                true to "Android Box đang khởi động lại..."
            }
            else -> false to "Lệnh không hỗ trợ: $command"
        }
    }
}
