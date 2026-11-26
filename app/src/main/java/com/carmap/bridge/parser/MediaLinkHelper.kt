package com.carmap.bridge.parser

import com.carmap.bridge.model.LinkKind
import com.carmap.bridge.model.MapProvider
import com.carmap.bridge.model.ParsedMapLink

data class DetectedMediaLink(
    val url: String,
    val serviceName: String,
    val title: String
)

object MediaLinkHelper {

    private val urlRegex = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)

    fun extractMediaLink(rawText: String): DetectedMediaLink? {
        val url = urlRegex.find(rawText)?.value?.trimEnd('.', ',', ';', ')') ?: return null
        val lower = url.lowercase()
        val service = when {
            lower.contains("music.youtube.com") -> "YouTube Music"
            lower.contains("youtube.com") || lower.contains("youtu.be") -> "YouTube"
            lower.contains("spotify.com") || lower.contains("spotify.link") -> "Spotify"
            lower.contains("zingmp3.vn") -> "Zing MP3"
            lower.contains("nhaccuatui.com") -> "NhacCuaTui"
            lower.contains("soundcloud.com") -> "SoundCloud"
            lower.contains("music.apple.com") -> "Apple Music"
            lower.contains("tiktok.com") -> "TikTok"
            else -> return null
        }
        val firstLine = rawText.lines()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("http", ignoreCase = true) }
            ?.take(60)
        val title = firstLine ?: "Mở trên $service"
        return DetectedMediaLink(url = url, serviceName = service, title = title)
    }

    private val shortRedirectHosts = setOf(
        "maps.app.goo.gl",
        "goo.gl",
        "g.co",
        "ul.waze.com",
        "bit.ly",
        "tinyurl.com"
    )

    fun isShortRedirectUrl(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull()
            ?: return false
        return host in shortRedirectHosts
    }

    fun isKnownMapUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("maps.google") ||
            lower.contains("maps.app.goo.gl") ||
            lower.contains("goo.gl/maps") ||
            lower.contains("google.com/maps") ||
            lower.contains("waze.com") ||
            lower.contains("vietmap.vn") ||
            lower.contains("maps.apple.com")
    }

    /**
     * Detects any general http(s) web URL that is NOT a map URL, known media URL,
     * or short redirect URL (unless allowShortRedirect=true after expansion).
     */
    fun extractGenericWebLink(rawText: String, allowShortRedirect: Boolean = false): String? {
        val url = urlRegex.find(rawText)?.value?.trimEnd('.', ',', ';', ')') ?: return null
        if (isKnownMapUrl(url) || extractMediaLink(rawText) != null) return null
        if (!allowShortRedirect && isShortRedirectUrl(url)) return null
        return url
    }

    fun toParsedRecord(rawText: String, media: DetectedMediaLink): ParsedMapLink {
        return ParsedMapLink(
            rawInput = rawText,
            extractedUrl = media.url,
            resolvedUrl = media.url,
            sourceProvider = MapProvider.UNKNOWN,
            kind = LinkKind.MEDIA,
            title = "${media.serviceName}: ${media.title}"
        )
    }

    fun toWebParsedRecord(rawText: String, url: String): ParsedMapLink {
        val host = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull().orEmpty()
        val firstLine = rawText.lines()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("http", ignoreCase = true) }
            ?.take(60)
        val title = firstLine ?: host.ifBlank { url.take(48) }
        return ParsedMapLink(
            rawInput = rawText,
            extractedUrl = url,
            resolvedUrl = url,
            sourceProvider = MapProvider.UNKNOWN,
            kind = LinkKind.WEB,
            title = "Web: $title"
        )
    }

    fun isLikelyMapOrMediaLink(text: String): Boolean {
        val t = text.trim()
        if (t.isBlank() || t.length > 1200) return false
        if (extractMediaLink(t) != null) return true
        if (extractGenericWebLink(t, allowShortRedirect = true) != null) return true
        val lower = t.lowercase()
        if (isKnownMapUrl(lower) || lower.startsWith("geo:")) {
            return true
        }
        val coordRegex = Regex("""^\s*-?\d{1,2}\.\d{3,}\s*,\s*-?\d{1,3}\.\d{3,}\s*$""")
        return coordRegex.matches(t)
    }
}
