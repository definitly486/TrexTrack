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

    /**
     * Zepp OS details are a sequence of one-byte type + one-byte payload length
     * records. GPS_DELTA carries a millisecond time offset followed by longitude
     * and latitude deltas. The point must be emitted AFTER applying that record.
     */
    fun parse(raw: ByteArray, workoutStartTimeMs: Long = 0L): List<GpsPoint> {
        if (raw.isEmpty()) return emptyList()
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val points = mutableListOf<GpsPoint>()
        var timestamp = workoutStartTimeMs
        var offset = 0L
        var lon = 0
        var lat = 0
        var hasCoords = false

        fun emit() {
            if (!hasCoords) return
            val latitude = lat / DIV
            val longitude = lon / DIV
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
                Log.w(TAG, "ignoring out-of-range GPS coordinate $latitude,$longitude")
                return
            }
            val previous = points.lastOrNull()
            if (previous != null &&
                previous.latitude == latitude &&
                previous.longitude == longitude &&
                previous.timeMs == timestamp + offset
            ) return
            points.add(GpsPoint(timestamp + offset, latitude, longitude))
        }

        while (buf.remaining() >= 2) {
            val typeCode = buf.get().toInt() and 0xff
            val length = buf.get().toInt() and 0xff
            if (length > buf.remaining()) {
                Log.w(TAG, "truncated details record type=$typeCode length=$length remaining=${buf.remaining()}")
                break
            }
            val start = buf.position()
            val end = start + length
            try {
                when (typeCode) {
                    TYPE_TIMESTAMP -> if (length == 12) {
                        buf.int // reserved
                        timestamp = buf.long
                        offset = 0L
                    }
                    TYPE_GPS_COORDS -> if (length == 20) {
                        buf.position(buf.position() + 6)
                        lon = buf.int
                        lat = buf.int
                        buf.position(end) // trailing reserved bytes
                        hasCoords = true
                        emit()
                    }
                    TYPE_GPS_DELTA -> if (length == 8) {
                        offset = buf.short.toLong()
                        lon += buf.short.toInt()
                        lat += buf.short.toInt()
                        buf.short // reserved (usually 2)
                        if (hasCoords) emit()
                    }
                    // STATUS, SPEED, ALTITUDE, HEART RATE and unknown records are
                    // currently not represented in GpsPoint, but are safely skipped.
                    else -> Unit
                }
            } catch (e: Exception) {
                Log.w(TAG, "parse error at $start for type=$typeCode: ${e.message}")
                break
            }
            if (buf.position() < end) buf.position(end)
            if (buf.position() > end) {
                Log.w(TAG, "record type=$typeCode over-read payload: end=$end pos=${buf.position()}")
                break
            }
        }

        Log.i(TAG, "parsed ${points.size} GPS points from ${raw.size} bytes")
        return points
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
