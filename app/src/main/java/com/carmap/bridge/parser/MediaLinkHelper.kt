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

    fun isLikelyMapOrMediaLink(text: String): Boolean {
        val t = text.trim()
        if (t.isBlank() || t.length > 1200) return false
        if (extractMediaLink(t) != null) return true
        val lower = t.lowercase()
        if (lower.contains("maps.google") ||
            lower.contains("maps.app.goo.gl") ||
            lower.contains("goo.gl/maps") ||
            lower.contains("google.com/maps") ||
            lower.contains("waze.com") ||
            lower.contains("vietmap.vn") ||
            lower.contains("maps.apple.com") ||
            lower.startsWith("geo:")
        ) {
            return true
        }
        val coordRegex = Regex("""^\s*-?\d{1,2}\.\d{3,}\s*,\s*-?\d{1,3}\.\d{3,}\s*$""")
        return coordRegex.matches(t)
    }
}
