package com.carmap.bridge.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.carmap.bridge.util.CarBridgeNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class InstalledAppVersion(
    val versionCode: Long,
    val versionName: String
)

data class RemoteReleaseInfo(
    val versionName: String,
    val versionCode: Long?,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val apkFileName: String,
    val apkSizeBytes: Long,
    val publishedAt: String
)

sealed interface OtaUpdateState {
    data object Idle : OtaUpdateState
    data object Checking : OtaUpdateState
    data class UpToDate(val currentVersion: String) : OtaUpdateState
    data class UpdateAvailable(val release: RemoteReleaseInfo) : OtaUpdateState
    data class Downloading(
        val release: RemoteReleaseInfo,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : OtaUpdateState {
        val progress: Float
            get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else -1f
    }
    data class ReadyToInstall(
        val release: RemoteReleaseInfo,
        val apkFile: File
    ) : OtaUpdateState
    data class Error(val message: String) : OtaUpdateState
}

object AppUpdater {

    const val DEFAULT_OTA_REPO = "BoizVN43/CarBridge"

    private const val PREFS_OTA = "carbridge_ota_cache"
    private const val KEY_NOTIFIED_VERSION = "notified_version_key"
    private const val KEY_AUTO_POPUP_SHOWN_VERSION = "auto_popup_shown_version_key"
    private const val KEY_CACHED_RELEASE_JSON = "cached_avail_release_json"
    private const val KEY_LAST_ETAG = "last_release_etag"
    private const val KEY_LAST_CHECK_MS = "last_ota_check_ms"

    private const val AUTO_CHECK_COOLDOWN_MS = 90_000L
    private const val MAX_REDIRECTS = 6

    private val _state = MutableStateFlow<OtaUpdateState>(OtaUpdateState.Idle)
    val state: StateFlow<OtaUpdateState> = _state.asStateFlow()

    private val _availableUpdate = MutableStateFlow<RemoteReleaseInfo?>(null)
    val availableUpdate: StateFlow<RemoteReleaseInfo?> = _availableUpdate.asStateFlow()

    private val checkInProgress = AtomicBoolean(false)
    private val downloadInProgress = AtomicBoolean(false)

    fun getInstalledVersion(context: Context): InstalledAppVersion {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            InstalledAppVersion(
                versionCode = PackageInfoCompat.getLongVersionCode(info),
                versionName = info.versionName ?: "1.1.0"
            )
        } catch (_: Exception) {
            InstalledAppVersion(versionCode = 2L, versionName = "1.1.0")
        }
    }

    fun loadCachedAvailability(context: Context) {
        val raw = context.getSharedPreferences(PREFS_OTA, Context.MODE_PRIVATE)
            .getString(KEY_CACHED_RELEASE_JSON, null)
            ?: return
        val cached = runCatching {
            val o = JSONObject(raw)
            RemoteReleaseInfo(
                versionName = o.getString("versionName"),
                versionCode = o.optLong("versionCode", -1L).takeIf { it > 0L },
                title = o.optString("title", ""),
                notes = o.optString("notes", ""),
                apkUrl = o.getString("apkUrl"),
                apkFileName = o.optString("apkFileName", "carbridge-update.apk"),
                apkSizeBytes = o.optLong("apkSizeBytes", 0L),
                publishedAt = o.optString("publishedAt", "")
            )
        }.getOrNull()

        val installed = getInstalledVersion(context)
        if (cached != null && isRemoteNewer(installed, cached)) {
            _availableUpdate.value = cached
        } else {
            clearCachedAvailability(context)
        }
    }

    private fun saveCachedAvailability(context: Context, release: RemoteReleaseInfo) {
        _availableUpdate.value = release
        val json = JSONObject().apply {
            put("versionName", release.versionName)
            put("versionCode", release.versionCode ?: -1L)
            put("title", release.title)
            put("notes", release.notes)
            put("apkUrl", release.apkUrl)
            put("apkFileName", release.apkFileName)
            put("apkSizeBytes", release.apkSizeBytes)
            put("publishedAt", release.publishedAt)
        }.toString()
        context.getSharedPreferences(PREFS_OTA, Context.MODE_PRIVATE)
            .edit().putString(KEY_CACHED_RELEASE_JSON, json).apply()
    }

    private fun clearCachedAvailability(context: Context) {
        _availableUpdate.value = null
        context.getSharedPreferences(PREFS_OTA, Context.MODE_PRIVATE)
            .edit().remove(KEY_CACHED_RELEASE_JSON).apply()
    }

    fun openUpdateDialog() {
        val rel = _availableUpdate.value
        if (rel != null) {
            _state.value = OtaUpdateState.UpdateAvailable(rel)
        }
    }

    fun dismissDialog() {
        _state.value = OtaUpdateState.Idle
    }

    fun cleanupOldCachedApks(context: Context) {
        runCatching {
            val dir = File(context.cacheDir, "updates")
            if (dir.exists()) {
                dir.listFiles()?.forEach { file ->
                    if (System.currentTimeMillis() - file.lastModified() > 24L * 3600_000L) {
                        file.delete()
                    }
                }
            }
        }
    }

    private fun presentAutoDialogIfUnseen(context: Context, release: RemoteReleaseInfo, autoShowDialog: Boolean) {
        val versionKey = "${release.versionCode ?: 0L}_${release.versionName}"
        val prefs = context.getSharedPreferences(PREFS_OTA, Context.MODE_PRIVATE)
        val lastNotified = prefs.getString(KEY_NOTIFIED_VERSION, null)
        if (lastNotified != versionKey) {
            prefs.edit().putString(KEY_NOTIFIED_VERSION, versionKey).apply()
            CarBridgeNotifier.notifyOtaUpdateAvailable(
                context = context,
                versionName = release.versionName,
                sizeLabel = formatBytesMb(release.apkSizeBytes)
            )
        }
        if (!autoShowDialog) return
        val lastAutoPopup = prefs.getString(KEY_AUTO_POPUP_SHOWN_VERSION, null)
        if (lastAutoPopup != versionKey && _state.value is OtaUpdateState.Idle) {
            prefs.edit().putString(KEY_AUTO_POPUP_SHOWN_VERSION, versionKey).apply()
            _state.value = OtaUpdateState.UpdateAvailable(release)
        }
    }

    suspend fun checkForUpdate(
        context: Context,
        silent: Boolean = false,
        forceBypassCooldown: Boolean = false,
        autoShowDialog: Boolean = false
    ): OtaUpdateState = withContext(Dispatchers.IO) {
        if (!checkInProgress.compareAndSet(false, true)) {
            return@withContext _state.value
        }
        try {
            val endpoint = "https://api.github.com/repos/$DEFAULT_OTA_REPO/releases/latest"
            val prefs = context.getSharedPreferences(PREFS_OTA, Context.MODE_PRIVATE)

            if (silent && !forceBypassCooldown) {
                val elapsed = System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK_MS, 0L)
                if (elapsed in 0L until AUTO_CHECK_COOLDOWN_MS) {
                    if (autoShowDialog) {
                        loadCachedAvailability(context)
                        _availableUpdate.value?.let { presentAutoDialogIfUnseen(context, it, true) }
                    }
                    return@withContext _state.value
                }
            }

            if (!silent) {
                _state.value = OtaUpdateState.Checking
            }

            val installed = getInstalledVersion(context)
            val savedEtag = if (silent) prefs.getString(KEY_LAST_ETAG, null) else null

            try {
                val fetchRes = fetchTextWithEtag(endpoint, savedEtag)
                prefs.edit().putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis()).apply()

                if (fetchRes.notModified) {
                    loadCachedAvailability(context)
                    val cached = _availableUpdate.value
                    return@withContext if (cached != null) {
                        presentAutoDialogIfUnseen(context, cached, autoShowDialog)
                        val available = OtaUpdateState.UpdateAvailable(cached)
                        if (!silent) _state.value = available
                        available
                    } else {
                        emitOutcome(silent, OtaUpdateState.UpToDate(installed.versionName))
                    }
                }

                if (!fetchRes.etag.isNullOrBlank()) {
                    prefs.edit().putString(KEY_LAST_ETAG, fetchRes.etag).apply()
                }

                val release = parseReleaseJson(fetchRes.body)
                    ?: return@withContext emitOutcome(
                        silent,
                        OtaUpdateState.Error("Bản phát hành mới nhất chưa có tệp .apk đính kèm.")
                    )

                if (isRemoteNewer(installed, release)) {
                    saveCachedAvailability(context, release)
                    presentAutoDialogIfUnseen(context, release, autoShowDialog)
                    val available = OtaUpdateState.UpdateAvailable(release)
                    if (!silent) _state.value = available
                    available
                } else {
                    clearCachedAvailability(context)
                    emitOutcome(silent, OtaUpdateState.UpToDate(installed.versionName))
                }
            } catch (e: Exception) {
                val msg = when {
                    e.message?.contains("404") == true ->
                        "Chưa tìm thấy bản phát hành nào trên GitHub (HTTP 404)."
                    e.message?.contains("403") == true ->
                        "GitHub tạm giới hạn lượt kiểm tra (HTTP 403). Thử lại sau vài phút."
                    else -> "Không thể kết nối kiểm tra cập nhật: ${e.localizedMessage ?: "lỗi mạng"}"
                }
                emitOutcome(silent, OtaUpdateState.Error(msg))
            }
        } finally {
            checkInProgress.set(false)
        }
    }

    private fun emitOutcome(silent: Boolean, state: OtaUpdateState): OtaUpdateState {
        if (!silent) _state.value = state
        return state
    }

    private fun parseReleaseJson(rawJson: String): RemoteReleaseInfo? {
        val obj = JSONObject(rawJson)
        val tagName = obj.optString("tag_name").trim()
        val title = obj.optString("name").trim().ifBlank { tagName }
        val body = obj.optString("body").trim()
        val publishedAt = obj.optString("published_at").trim().take(10)
        val assets = obj.optJSONArray("assets") ?: return null

        var bestUrl = ""
        var bestName = "carbridge-update.apk"
        var bestSize = 0L
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                bestUrl = asset.optString("browser_download_url")
                bestName = name
                bestSize = asset.optLong("size", 0L)
                break
            }
        }
        if (bestUrl.isBlank()) return null

        val cleanVersion = tagName.removePrefix("v").removePrefix("V")
            .substringBefore("-b").substringBefore("+").trim()
            .ifBlank { title.removePrefix("CarBridge").trim().removePrefix("v") }

        val codeFromTag = Regex("""-b(\d+)""", RegexOption.IGNORE_CASE).find(tagName)
            ?.groupValues?.getOrNull(1)?.toLongOrNull()
        val codeFromBody = Regex("""versionCode\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE).find(body)
            ?.groupValues?.getOrNull(1)?.toLongOrNull()

        return RemoteReleaseInfo(
            versionName = cleanVersion.ifBlank { tagName },
            versionCode = codeFromBody ?: codeFromTag,
            title = title.ifBlank { "CarBridge v$cleanVersion" },
            notes = body.ifBlank { "Cải thiện độ ổn định kết nối và giao diện." },
            apkUrl = bestUrl,
            apkFileName = bestName,
            apkSizeBytes = bestSize,
            publishedAt = publishedAt
        )
    }

    internal fun isRemoteNewer(installed: InstalledAppVersion, remote: RemoteReleaseInfo): Boolean {
        if (remote.versionCode != null) {
            return remote.versionCode > installed.versionCode
        }
        return compareSemVer(remote.versionName, installed.versionName) > 0
    }

    private fun compareSemVer(a: String, b: String): Int {
        val pa = a.removePrefix("v").removePrefix("V").split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val pb = b.removePrefix("v").removePrefix("V").split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val len = maxOf(pa.size, pb.size)
        for (i in 0 until len) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    suspend fun downloadUpdate(context: Context, release: RemoteReleaseInfo): File? = withContext(Dispatchers.IO) {
        if (!downloadInProgress.compareAndSet(false, true)) {
            return@withContext null
        }
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val partFile = File(updatesDir, "carbridge-update.apk.part")
        val finalFile = File(updatesDir, "carbridge-update.apk")
        if (partFile.exists()) partFile.delete()

        _state.value = OtaUpdateState.Downloading(release, 0L, release.apkSizeBytes)

        var conn: HttpURLConnection? = null
        try {
            conn = openWithRedirects(release.apkUrl, accept = "application/octet-stream")
            val status = conn.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("Máy chủ trả mã lỗi HTTP $status")
            }
            val total = conn.contentLengthLong.takeIf { it > 0L } ?: release.apkSizeBytes
            var downloaded = 0L
            var lastEmitMs = 0L

            conn.inputStream.buffered(32 * 1024).use { input ->
                partFile.outputStream().buffered(32 * 1024).use { output ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmitMs >= 120L || (total > 0L && downloaded >= total)) {
                            lastEmitMs = now
                            _state.value = OtaUpdateState.Downloading(release, downloaded, total)
                        }
                    }
                    output.flush()
                }
            }

            if (partFile.length() < 100_000L) {
                partFile.delete()
                throw IllegalStateException("Tệp APK tải về không hợp lệ (dung lượng quá nhỏ).")
            }

            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }

            _state.value = OtaUpdateState.ReadyToInstall(release, finalFile)
            finalFile
        } catch (e: Exception) {
            partFile.delete()
            _state.value = OtaUpdateState.Error("Lỗi tải bản cập nhật: ${e.localizedMessage ?: "không rõ"}")
            null
        } finally {
            conn?.disconnect()
            downloadInProgress.set(false)
        }
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }

    fun launchApkInstaller(context: Context, apkFile: File): Boolean {
        if (!canInstallPackages(context)) {
            openInstallPermissionSettings(context)
            return false
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }

    fun formatBytesMb(bytes: Long): String {
        if (bytes <= 0L) return ""
        return String.format(Locale("vi", "VN"), "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private data class EtagFetchResult(
        val notModified: Boolean,
        val body: String,
        val etag: String?
    )

    private fun fetchTextWithEtag(urlString: String, ifNoneMatch: String?): EtagFetchResult {
        val conn = openWithRedirects(
            urlString = urlString,
            accept = "application/vnd.github+json",
            ifNoneMatch = ifNoneMatch
        )
        return try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return EtagFetchResult(notModified = true, body = "", etag = ifNoneMatch)
            }
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val etag = conn.getHeaderField("ETag")
            EtagFetchResult(notModified = false, body = body, etag = etag)
        } finally {
            conn.disconnect()
        }
    }

    private fun openWithRedirects(
        urlString: String,
        accept: String,
        ifNoneMatch: String? = null
    ): HttpURLConnection {
        var currentUrl = urlString
        for (attempt in 0 until MAX_REDIRECTS) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "CarBridge-Android-Updater/1.1")
                setRequestProperty("Accept", accept)
                if (!ifNoneMatch.isNullOrBlank() && attempt == 0) {
                    setRequestProperty("If-None-Match", ifNoneMatch)
                }
            }
            val code = conn.responseCode
            if (code in 301..308) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) throw IllegalStateException("Redirect thiếu Location (HTTP $code)")
                currentUrl = URL(URL(currentUrl), loc).toString()
            } else {
                return conn
            }
        }
        throw IllegalStateException("Quá nhiều chuyển hướng mạng")
    }
}
