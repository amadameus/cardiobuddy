package com.nolan.cardiobuddy

// ─────────────────────────────────────────────────────────────────────────────
// Raw reading from the BLE device
// ─────────────────────────────────────────────────────────────────────────────

data class HrReading(val timestampMs: Long, val hr: Int)

// ─────────────────────────────────────────────────────────────────────────────
// Zones
// ─────────────────────────────────────────────────────────────────────────────

enum class ZoneLevel(
    val displayName: String,
    val shortName: String,
    val color: String
) {
    ZONE1("Zone 1 · Recovery",   "Z1", "#4CAF50"),
    ZONE2("Zone 2 · Aerobic",    "Z2", "#8BC34A"),
    ZONE3("Zone 3 · Cardio",     "Z3", "#FFC107"),
    ZONE4("Zone 4 · Threshold",  "Z4", "#FF5722"),
    ZONE5("Zone 5 · Peak",       "Z5", "#F44336")
}

data class ZoneConfig(val maxHr: Int) {
    val z1Ceil = (maxHr * 0.60).toInt()
    val z2Ceil = (maxHr * 0.70).toInt()
    val z3Ceil = (maxHr * 0.80).toInt()
    val z4Ceil = (maxHr * 0.90).toInt()

    fun zoneFor(hr: Int): ZoneLevel = when {
        hr < z1Ceil -> ZoneLevel.ZONE1
        hr < z2Ceil -> ZoneLevel.ZONE2
        hr < z3Ceil -> ZoneLevel.ZONE3
        hr < z4Ceil -> ZoneLevel.ZONE4
        else         -> ZoneLevel.ZONE5
    }

    /** Inclusive lower bound, exclusive upper bound (in BPM). Upper is open for Zone 5. */
    fun boundsLabel(zone: ZoneLevel): String = when (zone) {
        ZoneLevel.ZONE1 -> "< $z1Ceil BPM"
        ZoneLevel.ZONE2 -> "$z1Ceil–$z2Ceil BPM"
        ZoneLevel.ZONE3 -> "$z2Ceil–$z3Ceil BPM"
        ZoneLevel.ZONE4 -> "$z3Ceil–$z4Ceil BPM"
        ZoneLevel.ZONE5 -> "> $z4Ceil BPM"
    }

    fun lowerBound(zone: ZoneLevel): Int = when (zone) {
        ZoneLevel.ZONE1 -> 0
        ZoneLevel.ZONE2 -> z1Ceil
        ZoneLevel.ZONE3 -> z2Ceil
        ZoneLevel.ZONE4 -> z3Ceil
        ZoneLevel.ZONE5 -> z4Ceil
    }

    fun upperBound(zone: ZoneLevel, chartMax: Int): Int = when (zone) {
        ZoneLevel.ZONE1 -> z1Ceil
        ZoneLevel.ZONE2 -> z2Ceil
        ZoneLevel.ZONE3 -> z3Ceil
        ZoneLevel.ZONE4 -> z4Ceil
        ZoneLevel.ZONE5 -> chartMax
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Segments
// ─────────────────────────────────────────────────────────────────────────────

enum class SegmentType { WORKOUT, REST }

data class Segment(
    val type: SegmentType,
    val index: Int,                   // 1-based index within its type
    val readings: List<HrReading>
) {
    val startMs: Long get() = readings.first().timestampMs
    val endMs:   Long get() = readings.last().timestampMs
    val durationMs: Long get() = endMs - startMs

    val avgHr:  Int get() = if (readings.isEmpty()) 0
                            else readings.map { it.hr }.average().toInt()
    val peakHr: Int get() = readings.maxOfOrNull { it.hr } ?: 0

    /** BPM/min drop across this rest period. Positive = HR fell (good recovery). */
    val decayRateBpmPerMin: Double get() {
        if (type != SegmentType.REST || readings.size < 2) return 0.0
        val durationMin = durationMs / 60_000.0
        if (durationMin < 0.01) return 0.0
        return (readings.first().hr - readings.last().hr).toDouble() / durationMin
    }

    /** Time in ms spent in each zone during this segment. */
    fun zoneTimeMs(zones: ZoneConfig): Map<ZoneLevel, Long> {
        val map = ZoneLevel.values().associateWith { 0L }.toMutableMap()
        for (i in readings.indices) {
            val zone = zones.zoneFor(readings[i].hr)
            // Cap inter-reading gap at 5 s to avoid counting gaps as time
            val dur = if (i < readings.size - 1)
                minOf(readings[i + 1].timestampMs - readings[i].timestampMs, 5_000L)
            else 1_000L
            map[zone] = (map[zone] ?: 0L) + dur
        }
        return map
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Full session analysis result
// ─────────────────────────────────────────────────────────────────────────────

data class SessionAnalysis(
    val sessionName:      String,
    val sessionDateLabel: String,
    val readings:         List<HrReading>,
    val zones:            ZoneConfig,
    val segments:         List<Segment>,
    val q1Threshold:      Int           // 25th-percentile HR = estimated resting floor
) {
    val sessionStartMs: Long get() = readings.firstOrNull()?.timestampMs ?: 0L

    val totalDurationMs: Long = if (readings.size >= 2)
        readings.last().timestampMs - readings.first().timestampMs else 0L

    val avgHr:              Int = if (readings.isEmpty()) 0
                                  else readings.map { it.hr }.average().toInt()
    val peakHr:             Int = readings.maxOfOrNull { it.hr } ?: 0
    val minHr:              Int = readings.minOfOrNull { it.hr } ?: 0
    val estimatedRestingHr: Int = q1Threshold

    val workoutSegments: List<Segment> = segments.filter { it.type == SegmentType.WORKOUT }
    val restSegments:    List<Segment> = segments.filter { it.type == SegmentType.REST }
    val totalWorkoutMs:  Long          = workoutSegments.sumOf { it.durationMs }
    val totalRestMs:     Long          = restSegments.sumOf { it.durationMs }

    /** Overall time in each zone across the full session. */
    val overallZoneTimes: Map<ZoneLevel, Long> by lazy {
        val map = ZoneLevel.values().associateWith { 0L }.toMutableMap()
        for (i in readings.indices) {
            val zone = zones.zoneFor(readings[i].hr)
            val dur = if (i < readings.size - 1)
                minOf(readings[i + 1].timestampMs - readings[i].timestampMs, 5_000L)
            else 1_000L
            map[zone] = (map[zone] ?: 0L) + dur
        }
        map
    }

    /** HR value → milliseconds spent at that exact value. */
    val histogram: Map<Int, Long> by lazy {
        val map = mutableMapOf<Int, Long>()
        for (i in readings.indices) {
            val hr = readings[i].hr
            val dur = if (i < readings.size - 1)
                minOf(readings[i + 1].timestampMs - readings[i].timestampMs, 5_000L)
            else 1_000L
            map[hr] = (map[hr] ?: 0L) + dur
        }
        map
    }
}
