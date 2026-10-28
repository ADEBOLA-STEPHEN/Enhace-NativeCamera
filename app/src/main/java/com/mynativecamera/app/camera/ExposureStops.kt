package com.mynativecamera.app.camera

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Cinema-standard exposure stops.
 *
 * Sliders move in discrete 1/3-stop increments (like a real cinema camera) instead of raw
 * continuous values. The Step 3 engine filters these against the sensor's reported
 * SENSOR_INFO_SENSITIVITY_RANGE / SENSOR_INFO_EXPOSURE_TIME_RANGE and the frame duration
 * of the selected video mode.
 *
 * Pure Kotlin (no android.* types) so it is unit-testable and safe in Compose previews.
 */
object ExposureStops {

    /** 1/3-stop ISO ladder. */
    val STANDARD_ISO: List<Int> = listOf(
        25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
        1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800,
        16000, 20000, 25600,
    )

    /**
     * Shutter denominators (1/x s). Includes 1/48, 1/50, 1/60 and 1/96, 1/100, 1/120
     * so the 180-degree rule is reachable at 24/25/30 and 48/50/60 fps.
     */
    private val SHUTTER_DENOMINATORS: List<Int> = listOf(
        8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500,
        400, 320, 250, 200, 160, 125, 120, 100, 96, 80, 60, 50, 48, 40, 30, 25, 24,
    )

    /** Exposure times in nanoseconds, ascending (shortest/darkest first). */
    val STANDARD_SHUTTER_NS: List<Long> =
        SHUTTER_DENOMINATORS.map { NANOS_PER_SECOND / it }.sorted()

    /** ISO stops that fall inside the sensor range; always returns at least one value. */
    fun isoStopsFor(minIso: Int, maxIso: Int): List<Int> {
        val stops = STANDARD_ISO.filter { it in minIso..maxIso }
        return stops.ifEmpty { listOf(minIso.coerceAtLeast(1)) }
    }

    /**
     * Shutter stops inside the sensor range AND no longer than one frame
     * (an exposure longer than the frame duration would force the fps down).
     */
    fun shutterStopsFor(minNs: Long, maxNs: Long, maxFrameDurationNs: Long): List<Long> {
        val upper = minOf(maxNs, maxFrameDurationNs)
        val stops = STANDARD_SHUTTER_NS.filter { it in minNs..upper }
        return stops.ifEmpty { listOf(upper.coerceAtLeast(minNs)) }
    }

    fun nearestIndex(stops: List<Int>, value: Int): Int =
        stops.indices.minByOrNull { abs(stops[it] - value) } ?: 0

    fun nearestIndex(stops: List<Long>, value: Long): Int =
        stops.indices.minByOrNull { abs(stops[it] - value) } ?: 0

    /** Index of the exposure closest to the 180-degree shutter for [fps] (1 / (2 * fps)). */
    fun index180Degree(stops: List<Long>, fps: Int): Int =
        nearestIndex(stops, NANOS_PER_SECOND / (2L * fps.coerceAtLeast(1)))

    const val NANOS_PER_SECOND = 1_000_000_000L

    // ---- Defaults used before the sensor has been queried ---------------------------------
    const val DEFAULT_FPS = 30
    val DEFAULT_ISO_STOPS: List<Int> = isoStopsFor(100, 3200)
    val DEFAULT_SHUTTER_STOPS: List<Long> =
        shutterStopsFor(100_000L, 1_000_000_000L, NANOS_PER_SECOND / DEFAULT_FPS)
}

/** Display helpers shared by the HUD and the engine's logging. */
object ExposureFormat {

    /** 20_833_333 ns -> "1/48", 2_000_000_000 ns -> "2.0s". */
    fun shutter(ns: Long): String {
        if (ns <= 0L) return "--"
        if (ns >= ExposureStops.NANOS_PER_SECOND) {
            val tenths = (ns / 100_000_000.0).roundToLong()
            return "${tenths / 10}.${tenths % 10}s"
        }
        val denominator = (ExposureStops.NANOS_PER_SECOND.toDouble() / ns).roundToInt()
        return "1/$denominator"
    }

    /** Shutter angle = exposure x fps x 360. 1/48 @ 24 fps -> "180°". */
    fun shutterAngle(ns: Long, fps: Int): String {
        val angle = ns.toDouble() * fps * 360.0 / ExposureStops.NANOS_PER_SECOND
        val tenths = (angle * 10).roundToLong()
        return if (tenths % 10 == 0L) "${tenths / 10}°" else "${tenths / 10}.${tenths % 10}°"
    }

    /** Elapsed milliseconds -> "HH:MM:SS". */
    fun timecode(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return "${two(h)}:${two(m)}:${two(s)}"
    }

    fun bitrate(bps: Int): String = "${(bps / 1_000_000.0).roundToInt()} Mbps"

    private fun two(v: Long): String = if (v < 10) "0$v" else v.toString()
}
