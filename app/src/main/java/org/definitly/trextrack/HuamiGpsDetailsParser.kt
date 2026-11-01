package org.definitly.trextrack

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class GpsPoint(
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double
)

/**
 * Minimal Zepp OS activity details parser (GPS_COORDS + GPS_DELTA).
 * Based on Gadgetbridge ZeppOsActivityDetailsParser.
 */
object HuamiGpsDetailsParser {
    private const val TAG = "HuamiGpsDetails"
    private const val DIV = 3_000_000.0

    private const val TYPE_TIMESTAMP = 1
    private const val TYPE_GPS_COORDS = 2
    private const val TYPE_GPS_DELTA = 3

    fun parse(raw: ByteArray): List<GpsPoint> {
        if (raw.isEmpty()) return emptyList()
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val points = mutableListOf<GpsPoint>()
        var timestamp = 0L
        var offset = 0
        var lon = 0
        var lat = 0
        var hasCoords = false

        fun emit() {
            if (!hasCoords) return
            points.add(
                GpsPoint(
                    timeMs = timestamp + offset,
                    latitude = lat / DIV,
                    longitude = lon / DIV
                )
            )
        }

        while (buf.remaining() > 2) {
            val typeCode = try {
                consumeTag(buf)
            } catch (_: Exception) {
                break
            }
            val length = try {
                consumeLength(buf)
            } catch (_: Exception) {
                break
            }
            if (length < 0 || length > buf.remaining()) break
            val start = buf.position()
            try {
                when (typeCode) {
                    TYPE_TIMESTAMP -> {
                        if (length >= 12) {
                            emit()
                            buf.int // unknown
                            timestamp = buf.long
                            offset = 0
                        } else {
                            buf.position(start + length)
                        }
                    }
                    TYPE_GPS_COORDS -> {
                        if (length >= 14) {
                            buf.position(buf.position() + 6)
                            lon = buf.int
                            lat = buf.int
                            hasCoords = true
                            // skip rest of record
                            buf.position(start + length)
                        } else {
                            buf.position(start + length)
                        }
                    }
                    TYPE_GPS_DELTA -> {
                        if (length >= 8) {
                            emit()
                            offset = buf.short.toInt()
                            val lonD = buf.short.toInt()
                            val latD = buf.short.toInt()
                            buf.short // always 2?
                            if (hasCoords) {
                                lon += lonD
                                lat += latD
                            }
                            buf.position(start + length)
                        } else {
                            buf.position(start + length)
                        }
                    }
                    else -> buf.position(start + length)
                }
            } catch (e: Exception) {
                Log.w(TAG, "parse error at ${buf.position()}: ${e.message}")
                break
            }
            // ensure we advanced
            if (buf.position() < start + length) {
                buf.position(start + length)
            }
        }
        emit()
        Log.i(TAG, "parsed ${points.size} GPS points from ${raw.size} bytes")
        return points
    }

    private fun consumeTag(buf: ByteBuffer): Int {
        val first = buf.get().toInt() and 0xff
        if ((first and 0x1f) != 0x1f) return first
        var tag = first
        while (buf.hasRemaining()) {
            val b = buf.get().toInt() and 0xff
            tag = (tag shl 8) or b
            if ((b and 0x80) == 0) break
        }
        return tag
    }

    private fun consumeLength(buf: ByteBuffer): Int {
        val first = buf.get().toInt() and 0xff
        if ((first and 0x80) == 0) return first
        val n = first and 0x7f
        if (n == 0 || n > 4) throw IllegalStateException("bad length $n")
        var v = 0
        repeat(n) { v = (v shl 8) or (buf.get().toInt() and 0xff) }
        return v
    }

    fun toGpx(points: List<GpsPoint>, name: String = "T-Rex track"): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append(
            """<gpx version="1.1" creator="TrexTrack" xmlns="http://www.topografix.com/GPX/1/1">"""
        ).append('\n')
        sb.append("<trk><name>").append(xmlEsc(name)).append("</name><trkseg>\n")
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        for (p in points) {
            sb.append("""<trkpt lat="${p.latitude}" lon="${p.longitude}">""")
            if (p.timeMs > 0) sb.append("<time>").append(sdf.format(java.util.Date(p.timeMs))).append("</time>")
            sb.append("</trkpt>\n")
        }
        sb.append("</trkseg></trk></gpx>\n")
        return sb.toString()
    }

    private fun xmlEsc(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
