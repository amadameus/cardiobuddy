package com.nolan.cardiobuddy

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SessionAnalyzer {

    /**
     * Minimum duration for a rest period to count as a distinct break.
     * Shorter dips (red lights, slow corners) are absorbed into surrounding workout.
     */
    private const val MIN_REST_DURATION_MS = 90_000L   // 90 seconds

    // ─────────────────────────────────────────────────────────────────────────
    // Public entry point
    // ─────────────────────────────────────────────────────────────────────────

    fun analyzeFromCsv(
        csvFile: File,
        maxHr: Int,
        sessionName: String
    ): SessionAnalysis? {
        val readings = parseCsv(csvFile)
        if (readings.size < 10) return null               // not enough data

        val dateLabel = SimpleDateFormat("MMMM d, yyyy · h:mm a", Locale.US)
            .format(Date(readings.first().timestampMs))

        val zones     = ZoneConfig(maxHr)
        val q1        = percentile(readings.map { it.hr }, 25)
        val segments  = detectSegments(readings, q1)

        return SessionAnalysis(sessionName, dateLabel, readings, zones, segments, q1)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CSV parser  (format: timestamp_ms, timestamp_iso, heart_rate_bpm)
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseCsv(file: File): List<HrReading> {
        return try {
            file.readLines()
                .drop(1)
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split(",")
                    if (parts.size < 3) return@mapNotNull null
                    try {
                        HrReading(parts[0].trim().toLong(), parts[2].trim().toInt())
                    } catch (_: Exception) { null }
                }
        } catch (_: Exception) { emptyList() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Percentile helper
    // ─────────────────────────────────────────────────────────────────────────

    private fun percentile(values: List<Int>, pct: Int): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val idx = ((pct / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Segment detection
    // ─────────────────────────────────────────────────────────────────────────

    private fun detectSegments(readings: List<HrReading>, q1: Int): List<Segment> {
        if (readings.size < 2) {
            return listOf(Segment(SegmentType.WORKOUT, 1, readings))
        }

        // Step 1 — label every reading as rest (≤ Q1) or workout
        val isRest = readings.map { it.hr <= q1 }

        // Step 2 — group consecutive same-label readings into raw spans
        data class Span(val rest: Boolean, val from: Int, val to: Int)

        val rawSpans = mutableListOf<Span>()
        var spanStart = 0
        for (i in 1..readings.size) {
            val done  = i == readings.size
            val flips = !done && isRest[i] != isRest[spanStart]
            if (done || flips) {
                rawSpans += Span(isRest[spanStart], spanStart, i - 1)
                if (!done) spanStart = i
            }
        }

        // Step 3 — absorb short REST spans (< 90 s) into their preceding workout span.
        //           Repeat until stable (handles back-to-back micro-rests).
        val spans = rawSpans.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            val out = mutableListOf<Span>()
            for (s in spans) {
                val durMs = readings[s.to].timestampMs - readings[s.from].timestampMs
                if (s.rest && durMs < MIN_REST_DURATION_MS && out.isNotEmpty()) {
                    // Extend previous span to absorb this micro-rest
                    out[out.lastIndex] = out.last().copy(to = s.to, rest = false)
                    changed = true
                } else {
                    out += s
                }
            }
            spans.clear(); spans.addAll(out)
        }

        // Step 4 — merge any adjacent same-type spans that emerged from Step 3
        val merged = mutableListOf<Span>()
        for (s in spans) {
            if (merged.isNotEmpty() && merged.last().rest == s.rest)
                merged[merged.lastIndex] = merged.last().copy(to = s.to)
            else
                merged += s
        }

        // Step 5 — build Segment objects with 1-based per-type indices
        var workoutIdx = 0
        var restIdx    = 0
        return merged.map { span ->
            val type  = if (span.rest) SegmentType.REST else SegmentType.WORKOUT
            val index = if (span.rest) ++restIdx else ++workoutIdx
            Segment(type, index, readings.subList(span.from, span.to + 1))
        }
    }
}
