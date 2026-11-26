package com.carmap.bridge.util

import android.net.Uri
import com.carmap.bridge.model.DiscoveredBox
import com.carmap.bridge.model.TransportChannel
import com.carmap.bridge.network.BoxNetworkConstants

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
 * Pure-Kotlin QR Code encoder (Byte mode, Error Correction Level L, Versions 1..7)
 * Zero external library dependencies; renders standard scannable QR matrices for pairing URIs.
 */
object QrCodeMatrix {

    private data class VersionSpec(
        val version: Int,
        val size: Int,
        val dataCodewords: Int,
        val ecCodewordsPerBlock: Int,
        val blocks: Int,
        val alignmentCenters: IntArray
    )

    private val specs = listOf(
        VersionSpec(1, 21, 19, 7, 1, intArrayOf()),
        VersionSpec(2, 25, 34, 10, 1, intArrayOf(6, 18)),
        VersionSpec(3, 29, 55, 15, 1, intArrayOf(6, 22)),
        VersionSpec(4, 33, 80, 20, 1, intArrayOf(6, 26)),
        VersionSpec(5, 37, 108, 26, 1, intArrayOf(6, 30)),
        VersionSpec(6, 41, 136, 18, 2, intArrayOf(6, 34)),
        VersionSpec(7, 45, 156, 20, 2, intArrayOf(6, 22, 38))
    )

    fun encode(text: String): Array<BooleanArray>? {
        val data = text.toByteArray(Charsets.UTF_8)
        // Byte mode header: 4 bits mode (0100) + 8 bits length (for versions 1..9) + 4 bits terminator
        val spec = specs.firstOrNull { (data.size + 2) <= it.dataCodewords } ?: return null

        val bits = ArrayList<Boolean>(spec.dataCodewords * 8)
        appendBits(bits, 0b0100, 4)
        appendBits(bits, data.size, 8)
        for (b in data) appendBits(bits, b.toInt() and 0xFF, 8)

        val maxBits = spec.dataCodewords * 8
        val termLen = minOf(4, maxBits - bits.size)
        appendBits(bits, 0, termLen)
        while (bits.size % 8 != 0) bits.add(false)

        val padBytes = intArrayOf(0xEC, 0x11)
        var padIdx = 0
        while (bits.size < maxBits) {
            appendBits(bits, padBytes[padIdx % 2], 8)
            padIdx++
        }

        val dataCodewords = ByteArray(spec.dataCodewords) { i ->
            var v = 0
            for (b in 0 until 8) {
                v = (v shl 1) or if (bits[i * 8 + b]) 1 else 0
            }
            v.toByte()
        }

        val interleaved = interleaveWithEcc(dataCodewords, spec)
        val size = spec.size
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        placeFinder(modules, isFunction, 0, 0)
        placeFinder(modules, isFunction, size - 7, 0)
        placeFinder(modules, isFunction, 0, size - 7)

        for (i in 8 until size - 8) {
            setFunc(modules, isFunction, i, 6, i % 2 == 0)
            setFunc(modules, isFunction, 6, i, i % 2 == 0)
        }

        val centers = spec.alignmentCenters
        for (cy in centers) {
            for (cx in centers) {
                if ((cx == 6 && cy == 6) || (cx == 6 && cy == size - 7) || (cx == size - 7 && cy == 6)) continue
                placeAlignment(modules, isFunction, cx, cy)
            }
        }

        // Reserve format info & dark module
        for (i in 0..8) {
            if (i != 6) {
                setFunc(modules, isFunction, 8, i, false)
                setFunc(modules, isFunction, i, 8, false)
            }
        }
        for (i in 0..7) {
            setFunc(modules, isFunction, size - 1 - i, 8, false)
            setFunc(modules, isFunction, 8, size - 1 - i, false)
        }
        setFunc(modules, isFunction, 8, size - 8, true)

        if (spec.version >= 7) {
            placeVersionInfo(modules, isFunction, spec.version)
        }

        // Place data bits (Mask 0: (r + c) % 2 == 0)
        var bitIndex = 0
        val totalBits = interleaved.size * 8
        var up = true
        var col = size - 1
        while (col > 0) {
            if (col == 6) col--
            for (step in 0 until size) {
                val r = if (up) size - 1 - step else step
                for (dx in 0..1) {
                    val c = col - dx
                    if (!isFunction[r][c]) {
                        val bit = if (bitIndex < totalBits) {
                            ((interleaved[bitIndex ushr 3].toInt() ushr (7 - (bitIndex and 7))) and 1) != 0
                        } else false
                        bitIndex++
                        val mask = ((r + c) and 1) == 0
                        modules[r][c] = bit xor mask
                    }
                }
            }
            up = !up
            col -= 2
        }

        // Format info for ECC Level L (01) + Mask 0 (000) -> 15-bit BCH + XOR 0x5412 = 0x77C4
        val formatBits = 0x77C4
        for (i in 0..5) setFunc(modules, isFunction, 8, i, ((formatBits ushr i) and 1) != 0)
        setFunc(modules, isFunction, 8, 7, ((formatBits ushr 6) and 1) != 0)
        setFunc(modules, isFunction, 8, 8, ((formatBits ushr 7) and 1) != 0)
        setFunc(modules, isFunction, 7, 8, ((formatBits ushr 8) and 1) != 0)
        for (i in 9..14) setFunc(modules, isFunction, 14 - i, 8, ((formatBits ushr i) and 1) != 0)

        for (i in 0..7) setFunc(modules, isFunction, size - 1 - i, 8, ((formatBits ushr i) and 1) != 0)
        for (i in 8..14) setFunc(modules, isFunction, 8, size - 15 + i, ((formatBits ushr i) and 1) != 0)

        return modules
    }

    private fun appendBits(list: MutableList<Boolean>, value: Int, count: Int) {
        for (i in count - 1 downTo 0) {
            list.add(((value ushr i) and 1) != 0)
        }
    }

    private fun setFunc(modules: Array<BooleanArray>, isFunc: Array<BooleanArray>, x: Int, y: Int, v: Boolean) {
        modules[y][x] = v
        isFunc[y][x] = true
    }

    private fun placeFinder(modules: Array<BooleanArray>, isFunc: Array<BooleanArray>, x: Int, y: Int) {
        val size = modules.size
        for (dy in -1..7) {
            for (dx in -1..7) {
                val xx = x + dx
                val yy = y + dy
                if (xx !in 0 until size || yy !in 0 until size) continue
                val dark = dx in 0..6 && dy in 0..6 && (
                    dx == 0 || dx == 6 || dy == 0 || dy == 6 || (dx in 2..4 && dy in 2..4)
                    )
                setFunc(modules, isFunc, xx, yy, dark)
            }
        }
    }

    private fun placeAlignment(modules: Array<BooleanArray>, isFunc: Array<BooleanArray>, cx: Int, cy: Int) {
        for (dy in -2..2) {
            for (dx in -2..2) {
                val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                setFunc(modules, isFunc, cx + dx, cy + dy, dist != 1)
            }
        }
    }

    private fun placeVersionInfo(modules: Array<BooleanArray>, isFunc: Array<BooleanArray>, version: Int) {
        // Version 7 BCH(18,6) = 0x07C94
        val vBits = if (version == 7) 0x07C94 else return
        val size = modules.size
        for (i in 0 until 18) {
            val bit = ((vBits ushr i) and 1) != 0
            val a = size - 11 + (i % 3)
            val b = i / 3
            setFunc(modules, isFunc, a, b, bit)
            setFunc(modules, isFunc, b, a, bit)
        }
    }

    private fun interleaveWithEcc(data: ByteArray, spec: VersionSpec): ByteArray {
        val blockLen = data.size / spec.blocks
        val dataBlocks = Array(spec.blocks) { i ->
            data.copyOfRange(i * blockLen, (i + 1) * blockLen)
        }
        val gen = reedSolomonGenerator(spec.ecCodewordsPerBlock)
        val ecBlocks = Array(spec.blocks) { i ->
            reedSolomonRemainder(dataBlocks[i], gen)
        }
        val out = ByteArray(data.size + spec.blocks * spec.ecCodewordsPerBlock)
        var idx = 0
        for (i in 0 until blockLen) {
            for (b in 0 until spec.blocks) out[idx++] = dataBlocks[b][i]
        }
        for (i in 0 until spec.ecCodewordsPerBlock) {
            for (b in 0 until spec.blocks) out[idx++] = ecBlocks[b][i]
        }
        return out
    }

    private fun reedSolomonGenerator(degree: Int): IntArray {
        var poly = intArrayOf(1)
        var root = 1
        for (i in 0 until degree) {
            val next = IntArray(poly.size + 1)
            for (j in poly.indices) {
                next[j] = next[j] xor poly[j]
                next[j + 1] = next[j + 1] xor gfMul(poly[j], root)
            }
            poly = next
            root = gfMul(root, 2)
        }
        return poly
    }

    private fun reedSolomonRemainder(data: ByteArray, gen: IntArray): ByteArray {
        val ecLen = gen.size - 1
        val rem = IntArray(ecLen)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor rem[0]
            System.arraycopy(rem, 1, rem, 0, ecLen - 1)
            rem[ecLen - 1] = 0
            for (i in 0 until ecLen) {
                rem[i] = rem[i] xor gfMul(gen[i + 1], factor)
            }
        }
        return ByteArray(ecLen) { rem[it].toByte() }
    }

    private fun gfMul(a: Int, b: Int): Int {
        var x = a
        var y = b
        var res = 0
        while (y > 0) {
            if ((y and 1) != 0) res = res xor x
            x = x shl 1
            if ((x and 0x100) != 0) x = x xor 0x11D
            y = y ushr 1
        }
        return res
    }
}
