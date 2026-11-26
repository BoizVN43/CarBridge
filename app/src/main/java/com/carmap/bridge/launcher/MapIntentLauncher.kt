package com.carmap.bridge.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.carmap.bridge.model.LinkKind
import com.carmap.bridge.model.MapProvider
import com.carmap.bridge.model.ParsedMapLink
import java.net.URLEncoder

data class LaunchSpec(
    val effectiveProvider: MapProvider,
    val targetPackage: String?,
    val intentUri: String,
    val isPackageInstalled: Boolean
)

object MapIntentLauncher {

    fun buildLaunchSpec(
        context: Context,
        parsed: ParsedMapLink,
        preferredTarget: MapProvider
    ): LaunchSpec {
        val desired = when {
            preferredTarget != MapProvider.AUTO -> preferredTarget
            parsed.sourceProvider != MapProvider.UNKNOWN -> parsed.sourceProvider
            else -> MapProvider.GOOGLE_MAPS
        }

        // If desired map app isn't installed on Box, automatically fall back to any installed map app
        val installedDesiredPkg = findInstalledPackage(context, desired.packageNames)
        val (effective, installedPkg) = if (installedDesiredPkg != null) {
            desired to installedDesiredPkg
        } else {
            val fallbackProvider = listOf(MapProvider.GOOGLE_MAPS, MapProvider.WAZE, MapProvider.VIETMAP)
                .firstOrNull { findInstalledPackage(context, it.packageNames) != null }
            if (fallbackProvider != null) {
                fallbackProvider to findInstalledPackage(context, fallbackProvider.packageNames)
            } else {
                desired to null
            }
        }

        val uriString = buildUriForProvider(parsed, effective)

        return LaunchSpec(
            effectiveProvider = effective,
            targetPackage = installedPkg ?: effective.packageNames.firstOrNull(),
            intentUri = uriString,
            isPackageInstalled = installedPkg != null
        )
    }

    fun launchRealMapApp(context: Context, spec: LaunchSpec): Boolean {
        val uri = Uri.parse(spec.intentUri)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (spec.isPackageInstalled && !spec.targetPackage.isNullOrBlank()) {
                setPackage(spec.targetPackage)
            }
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun buildUriForProvider(parsed: ParsedMapLink, target: MapProvider): String {
        val lat = parsed.primaryLat
        val lng = parsed.primaryLng
        val destLabel = parsed.destination?.label ?: parsed.title

        return when (target) {
            MapProvider.WAZE -> {
                if (lat != null && lng != null) {
                    "https://waze.com/ul?ll=$lat,$lng&navigate=yes"
                } else {
                    "https://waze.com/ul?q=${enc(destLabel)}&navigate=yes"
                }
            }

            MapProvider.VIETMAP -> {
                if (parsed.sourceProvider == MapProvider.VIETMAP && parsed.resolvedUrl.startsWith("http")) {
                    parsed.resolvedUrl
                } else if (lat != null && lng != null) {
                    "geo:$lat,$lng?q=$lat,$lng(${enc(destLabel)})"
                } else {
                    "geo:0,0?q=${enc(destLabel)}"
                }
            }

            else -> {
                // Google Maps: always start turn-by-turn driving navigation immediately!
                if (parsed.kind == LinkKind.ROUTE && parsed.waypoints.isNotEmpty()) {
                    val destArg = parsed.destination?.let {
                        if (it.hasCoords()) "${it.lat},${it.lng}" else it.label
                    } ?: destLabel
                    val sb = StringBuilder(
                        "https://www.google.com/maps/dir/?api=1&destination=${enc(destArg)}&travelmode=driving&dir_action=navigate"
                    )
                    parsed.origin?.let { o ->
                        if (o.label != "Vị trí hiện tại") {
                            val oArg = if (o.hasCoords()) "${o.lat},${o.lng}" else o.label
                            sb.append("&origin=").append(enc(oArg))
                        }
                    }
                    val wpArg = parsed.waypoints.joinToString("|") { wp ->
                        if (wp.hasCoords()) "${wp.lat},${wp.lng}" else wp.label
                    }
                    sb.append("&waypoints=").append(enc(wpArg))
                    sb.toString()
                } else if (lat != null && lng != null) {
                    "google.navigation:q=$lat,$lng&mode=d"
                } else if (destLabel.isNotBlank() && destLabel != "Điểm đến") {
                    "google.navigation:q=${enc(destLabel)}&mode=d"
                } else if (parsed.resolvedUrl.startsWith("http")) {
                    parsed.resolvedUrl
                } else {
                    "google.navigation:q=${enc(parsed.rawInput.trim())}&mode=d"
                }
            }
        }
    }

    fun isAnyMapAppInstalled(context: Context): Map<MapProvider, Boolean> = mapOf(
        MapProvider.GOOGLE_MAPS to (findInstalledPackage(context, MapProvider.GOOGLE_MAPS.packageNames) != null),
        MapProvider.WAZE to (findInstalledPackage(context, MapProvider.WAZE.packageNames) != null),
        MapProvider.VIETMAP to (findInstalledPackage(context, MapProvider.VIETMAP.packageNames) != null)
    )

    private fun findInstalledPackage(context: Context, packages: List<String>): String? {
        val pm = context.packageManager
        return packages.firstOrNull { pkg ->
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }
                true
            }.getOrDefault(false)
        }
    }

    private fun enc(value: String): String =
        runCatching { URLEncoder.encode(value, "UTF-8") }.getOrDefault(value)
}
