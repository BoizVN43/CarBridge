package com.carmap.bridge.parser

import android.net.Uri
import com.carmap.bridge.model.LinkKind
import com.carmap.bridge.model.MapProvider
import com.carmap.bridge.model.MapWaypoint
import com.carmap.bridge.model.ParsedMapLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

object MapLinkResolver {

    private val urlRegex = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)
    private val coordRegex = Regex("""(-?\d{1,2}\.\d{3,})\s*,\s*(-?\d{1,3}\.\d{3,})""")
    private val atCoordRegex = Regex("""@(-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)""")
    private val bangCoordRegex = Regex("""!3d(-?\d{1,2}\.\d+)!4d(-?\d{1,3}\.\d+)""")
    private val wayBangCoordRegex = Regex("""!1d(-?\d{1,3}\.\d+)!2d(-?\d{1,2}\.\d+)""")

    private val shortHosts = setOf(
        "maps.app.goo.gl",
        "goo.gl",
        "g.co",
        "ul.waze.com",
        "bit.ly",
        "tinyurl.com"
    )

    private val ogUrlRegex = Regex(
        """<meta\s+(?:property|name)=["']og:url["']\s+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )
    private val parenLabelRegex = Regex("""-?\d{1,2}\.\d+\s*,\s*-?\d{1,3}\.\d+\s*\(([^)]+)\)""")
    private val genericPrefixes = setOf(
        "đã ghim vị trí",
        "dropped pin",
        "shared route",
        "tuyến đường được chia sẻ",
        "vị trí được chia sẻ"
    )

    suspend fun resolveAndParse(rawInput: String, preResolvedUrl: String = ""): ParsedMapLink = withContext(Dispatchers.IO) {
        val trimmed = rawInput.trim()
        val extractedUrl = urlRegex.find(trimmed)?.value
        val resolvedUrl = when {
            preResolvedUrl.isNotBlank() -> preResolvedUrl
            extractedUrl != null && needsRedirectExpansion(extractedUrl) -> expandRedirects(extractedUrl)
            else -> extractedUrl ?: trimmed
        }

        parseResolved(trimmed, extractedUrl, resolvedUrl)
    }

    fun parseImmediate(rawInput: String): ParsedMapLink {
        val trimmed = rawInput.trim()
        val extractedUrl = urlRegex.find(trimmed)?.value
        return parseResolved(trimmed, extractedUrl, extractedUrl ?: trimmed)
    }

    private fun needsRedirectExpansion(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return false
        return host in shortHosts || (host == "waze.com" && url.contains("/ul"))
    }

    private fun expandRedirects(startUrl: String): String {
        var current = startUrl
        repeat(6) {
            // Unwrap Google consent wrapper if encountered
            if (current.contains("consent.google.com", ignoreCase = true)) {
                val cont = runCatching { Uri.parse(current).getQueryParameter("continue") }.getOrNull()
                if (!cont.isNullOrBlank() && cont != current) {
                    current = cont
                    return@repeat
                }
            }
            val next = runCatching {
                val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    connectTimeout = 3500
                    readTimeout = 3500
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36"
                    )
                }
                try {
                    val code = conn.responseCode
                    val location = conn.getHeaderField("Location")
                    if (code in 300..399 && !location.isNullOrBlank()) {
                        URL(URL(current), location).toString()
                    } else if (code == 200) {
                        val snippet = conn.inputStream.bufferedReader().use { r ->
                            val buf = CharArray(16384)
                            val read = r.read(buf)
                            if (read > 0) String(buf, 0, read) else ""
                        }
                        ogUrlRegex.find(snippet)?.groupValues?.getOrNull(1)
                            ?.replace("&amp;", "&")
                    } else {
                        null
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()

            if (next.isNullOrBlank() || next == current) return current
            current = next
        }
        return current
    }

    private fun parseResolved(
        rawInput: String,
        extractedUrl: String?,
        resolvedUrl: String
    ): ParsedMapLink {
        val provider = detectProvider(resolvedUrl, extractedUrl ?: rawInput)
        val uri = runCatching { Uri.parse(resolvedUrl) }.getOrNull()
        val prefixText = extractedUrl?.let {
            rawInput.replace(it, "").lines()
                .firstOrNull { line -> line.isNotBlank() }
                ?.trim()
                ?.takeUnless { candidate -> candidate.lowercase(Locale.ROOT) in genericPrefixes }
        }

        // 1. Google Maps route: /maps/dir/?api=1&origin=...&destination=...&waypoints=...
        if (uri != null && (resolvedUrl.contains("/maps/dir", ignoreCase = true) ||
                uri.getQueryParameter("destination") != null)
        ) {
            val routeParsed = parseDirections(uri, rawInput, extractedUrl, resolvedUrl, provider, prefixText)
            if (routeParsed != null) return routeParsed
        }

        // 2. Waze URL: waze.com/ul?ll=lat,lng&navigate=yes or to=ll.lat,lng
        if (provider == MapProvider.WAZE && uri != null) {
            val ll = uri.getQueryParameter("ll")
                ?: uri.getQueryParameter("to")?.removePrefix("ll.")
            val q = uri.getQueryParameter("q")
            val coords = ll?.let { extractCoords(it) } ?: extractCoords(resolvedUrl)
            val title = prefixText ?: q ?: coords?.let { "Điểm Waze (${formatCoords(it)})" } ?: "Điểm đến Waze"
            val wp = MapWaypoint(title, coords?.first, coords?.second)
            return ParsedMapLink(
                rawInput = rawInput,
                extractedUrl = extractedUrl,
                resolvedUrl = resolvedUrl,
                sourceProvider = provider,
                kind = if (coords != null && q == null) LinkKind.COORDINATES else LinkKind.PLACE,
                title = title,
                destination = wp,
                primaryLat = coords?.first,
                primaryLng = coords?.second
            )
        }

        // 3. Vietmap URL
        if (provider == MapProvider.VIETMAP && uri != null) {
            val lat = uri.getQueryParameter("lat")?.toDoubleOrNull()
            val lng = uri.getQueryParameter("lng")?.toDoubleOrNull()
                ?: uri.getQueryParameter("lon")?.toDoubleOrNull()
            val daddr = uri.getQueryParameter("daddr")
            val q = uri.getQueryParameter("q") ?: uri.getQueryParameter("name")
            val coords = if (lat != null && lng != null) lat to lng else extractCoords(resolvedUrl)
            val title = prefixText ?: q ?: daddr ?: coords?.let { "Điểm Vietmap (${formatCoords(it)})" } ?: "Điểm đến Vietmap"
            val wp = MapWaypoint(title, coords?.first, coords?.second)
            return ParsedMapLink(
                rawInput = rawInput,
                extractedUrl = extractedUrl,
                resolvedUrl = resolvedUrl,
                sourceProvider = provider,
                kind = if (daddr != null) LinkKind.ROUTE else if (coords != null) LinkKind.COORDINATES else LinkKind.PLACE,
                title = title,
                destination = wp,
                primaryLat = coords?.first,
                primaryLng = coords?.second
            )
        }

        // 4. Apple Maps URL: maps.apple.com/?ll=lat,lng&q=...
        if (resolvedUrl.contains("maps.apple.com", ignoreCase = true) && uri != null) {
            val ll = uri.getQueryParameter("ll") ?: uri.getQueryParameter("daddr")
            val q = uri.getQueryParameter("q")
            val coords = ll?.let { extractCoords(it) } ?: extractCoords(resolvedUrl)
            val title = prefixText ?: q ?: coords?.let { "Điểm Apple Maps (${formatCoords(it)})" } ?: "Điểm đến bản đồ"
            val wp = MapWaypoint(title, coords?.first, coords?.second)
            return ParsedMapLink(
                rawInput = rawInput,
                extractedUrl = extractedUrl,
                resolvedUrl = resolvedUrl,
                sourceProvider = MapProvider.GOOGLE_MAPS,
                kind = if (coords != null && q == null) LinkKind.COORDINATES else LinkKind.PLACE,
                title = title,
                destination = wp,
                primaryLat = coords?.first,
                primaryLng = coords?.second
            )
        }

        // 5. Geo URI or place URL / raw coordinates
        val coords = extractCoords(resolvedUrl) ?: extractCoords(rawInput)
        val placeFromPath = extractGooglePlaceName(uri)
        val queryParam = uri?.let {
            it.getQueryParameter("q") ?: it.getQueryParameter("query") ?: it.getQueryParameter("destination")
        }
        val labelInParens = queryParam?.let { parenLabelRegex.find(it)?.groupValues?.getOrNull(1)?.trim() }
        val cleanQuery = labelInParens ?: queryParam?.takeUnless { coordRegex.matches(it.trim()) }
        val title = listOfNotNull(prefixText, placeFromPath, cleanQuery)
            .firstOrNull { it.isNotBlank() && !it.startsWith("http") }
            ?: coords?.let { "Tọa độ ${formatCoords(it)}" }
            ?: "Điểm đến bản đồ"

        val kind = when {
            extractedUrl == null && coords != null -> LinkKind.COORDINATES
            coords != null && placeFromPath == null && prefixText == null && queryParam == null -> LinkKind.COORDINATES
            extractedUrl != null -> LinkKind.PLACE
            else -> LinkKind.UNKNOWN
        }

        val dest = MapWaypoint(title, coords?.first, coords?.second)
        return ParsedMapLink(
            rawInput = rawInput,
            extractedUrl = extractedUrl,
            resolvedUrl = resolvedUrl,
            sourceProvider = provider,
            kind = kind,
            title = title,
            destination = dest,
            primaryLat = coords?.first,
            primaryLng = coords?.second
        )
    }

    private fun parseDirections(
        uri: Uri,
        rawInput: String,
        extractedUrl: String?,
        resolvedUrl: String,
        provider: MapProvider,
        prefixText: String?
    ): ParsedMapLink? {
        val originParam = uri.getQueryParameter("origin") ?: uri.getQueryParameter("saddr")
        val destParam = uri.getQueryParameter("destination") ?: uri.getQueryParameter("daddr")
        val waypointsParam = uri.getQueryParameter("waypoints")

        if (!destParam.isNullOrBlank()) {
            val originWp = originParam?.let { toWaypoint(it) }
            val destWp = toWaypoint(destParam)
            val stops = waypointsParam
                ?.split("|")
                ?.mapNotNull { it.trim().takeIf(String::isNotEmpty)?.let(::toWaypoint) }
                ?: emptyList()
            val fallbackCoords = extractCoords(resolvedUrl)
            return ParsedMapLink(
                rawInput = rawInput,
                extractedUrl = extractedUrl,
                resolvedUrl = resolvedUrl,
                sourceProvider = provider,
                kind = LinkKind.ROUTE,
                title = prefixText ?: "Lộ trình → ${destWp.label}",
                origin = originWp,
                destination = destWp,
                waypoints = stops,
                primaryLat = destWp.lat ?: fallbackCoords?.first,
                primaryLng = destWp.lng ?: fallbackCoords?.second
            )
        }

        // Path-based Google Maps /maps/dir/<origin>/<stop1>/<dest>/@lat,lng...
        val segments = uri.pathSegments.orEmpty()
        val dirIdx = segments.indexOf("dir")
        if (dirIdx >= 0) {
            val rawStops = segments.drop(dirIdx + 1)
                .takeWhile { !it.startsWith("@") && !it.startsWith("data=") }
                .map { decodeSeg(it) }
                .filter { it.isNotBlank() }

            val bangPoints = wayBangCoordRegex.findAll(resolvedUrl).map {
                val lng = it.groupValues[1].toDoubleOrNull()
                val lat = it.groupValues[2].toDoubleOrNull()
                lat to lng
            }.toList()

            if (rawStops.isNotEmpty()) {
                val parsedStops = rawStops.mapIndexed { idx, label ->
                    val segCoords = extractCoords(label)
                    val bang = bangPoints.getOrNull(idx)
                    MapWaypoint(
                        label = if (label == "Current Location" || label == "Vị trí của bạn") "Vị trí hiện tại" else label,
                        lat = segCoords?.first ?: bang?.first,
                        lng = segCoords?.second ?: bang?.second
                    )
                }
                val origin = if (parsedStops.size >= 2) parsedStops.first() else null
                val dest = parsedStops.last()
                val mids = if (parsedStops.size >= 3) parsedStops.subList(1, parsedStops.size - 1) else emptyList()
                val fallbackCoords = extractCoords(resolvedUrl)
                return ParsedMapLink(
                    rawInput = rawInput,
                    extractedUrl = extractedUrl,
                    resolvedUrl = resolvedUrl,
                    sourceProvider = provider,
                    kind = LinkKind.ROUTE,
                    title = prefixText ?: "Lộ trình → ${dest.label}",
                    origin = origin,
                    destination = dest,
                    waypoints = mids,
                    primaryLat = dest.lat ?: fallbackCoords?.first,
                    primaryLng = dest.lng ?: fallbackCoords?.second
                )
            }
        }
        return null
    }

    private fun toWaypoint(raw: String): MapWaypoint {
        val decoded = decodeSeg(raw)
        val c = extractCoords(decoded)
        return MapWaypoint(decoded, c?.first, c?.second)
    }

    private fun detectProvider(resolvedUrl: String, rawText: String): MapProvider {
        val text = "$resolvedUrl $rawText".lowercase(Locale.ROOT)
        return when {
            text.contains("waze.com") -> MapProvider.WAZE
            text.contains("vietmap") -> MapProvider.VIETMAP
            text.contains("google.") || text.contains("goo.gl") || text.contains("g.co") -> MapProvider.GOOGLE_MAPS
            text.startsWith("geo:") || coordRegex.containsMatchIn(rawText) -> MapProvider.GOOGLE_MAPS
            else -> MapProvider.UNKNOWN
        }
    }

    private fun extractGooglePlaceName(uri: Uri?): String? {
        if (uri == null) return null
        val segments = uri.pathSegments.orEmpty()
        val placeIdx = segments.indexOf("place")
        if (placeIdx >= 0 && placeIdx + 1 < segments.size) {
            return decodeSeg(segments[placeIdx + 1])
        }
        return null
    }

    private fun extractCoords(input: String): Pair<Double, Double>? {
        bangCoordRegex.find(input)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull()
            val lng = m.groupValues[2].toDoubleOrNull()
            if (lat != null && lng != null) return lat to lng
        }
        atCoordRegex.find(input)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull()
            val lng = m.groupValues[2].toDoubleOrNull()
            if (lat != null && lng != null) return lat to lng
        }
        coordRegex.find(input)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull()
            val lng = m.groupValues[2].toDoubleOrNull()
            if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                return lat to lng
            }
        }
        return null
    }

    private fun decodeSeg(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", " "), "UTF-8") }.getOrDefault(value)

    private fun formatCoords(coords: Pair<Double, Double>): String =
        String.format(Locale.US, "%.5f, %.5f", coords.first, coords.second)
}
