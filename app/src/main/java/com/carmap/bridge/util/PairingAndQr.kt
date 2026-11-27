package com.carmap.bridge.util

import android.net.Uri
import com.carmap.bridge.model.DiscoveredBox
import com.carmap.bridge.model.TransportChannel
import com.carmap.bridge.network.BoxNetworkConstants
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

data class ParsedPairingCode(
    val box: DiscoveredBox,
    val pin: String
)

object PairingCodeHelper {
    const val SCHEME = "carbridge"

    fun buildPairingUri(
        boxId: String,
        boxName: String,
        host: String,
        port: Int = BoxNetworkConstants.TCP_PORT,
        pin: String
    ): String {
        return Uri.Builder()
            .scheme(SCHEME)
            .authority("pair")
            .appendQueryParameter("id", boxId)
            .appendQueryParameter("name", boxName)
            .appendQueryParameter("host", host)
            .appendQueryParameter("port", port.toString())
            .appendQueryParameter("pin", pin)
            .build()
            .toString()
    }

    fun parsePairingUri(raw: String): ParsedPairingCode? {
        val trimmed = raw.trim()
        val startIdx = trimmed.indexOf("carbridge://pair", ignoreCase = true)
        if (startIdx < 0) return null
        val token = trimmed.substring(startIdx).substringBefore(' ').substringBefore('\n').trim()
        val uri = runCatching { Uri.parse(token) }.getOrNull() ?: return null
        val host = uri.getQueryParameter("host")?.trim().orEmpty()
        if (host.isBlank()) return null
        val port = uri.getQueryParameter("port")?.toIntOrNull() ?: BoxNetworkConstants.TCP_PORT
        val name = uri.getQueryParameter("name")?.trim().orEmpty().ifBlank { "Android Box ($host)" }
        val id = uri.getQueryParameter("id")?.trim().orEmpty().ifBlank { "qr-$host" }
        val pin = uri.getQueryParameter("pin")?.trim().orEmpty()
        return ParsedPairingCode(
            box = DiscoveredBox(
                id = id,
                name = name,
                host = host,
                port = port,
                channel = TransportChannel.LAN
            ),
            pin = pin
        )
    }
}

/**
 * QR Code encoder backed by ZXing QRCodeWriter (UTF-8, ECC Level M, supports any Box name length).
 */
object QrCodeMatrix {
    fun encode(text: String): Array<BooleanArray>? = runCatching {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 0
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        Array(matrix.height) { y ->
            BooleanArray(matrix.width) { x -> matrix.get(x, y) }
        }
    }.getOrNull()
}
