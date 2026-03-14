package com.nolan.cardiobuddy

import java.io.File
import java.util.concurrent.TimeUnit

object ReportGenerator {

    fun generate(analysis: SessionAnalysis, outputDir: File): File {
        val html = buildHtml(analysis)
        val safe = analysis.sessionName.replace(Regex("[^a-zA-Z0-9_\\- ]"), "").trim()
        val file = File(outputDir, "$safe.html")
        file.writeText(html)
        return file
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Top-level HTML assembly
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildHtml(a: SessionAnalysis): String {
        val traceSvg      = buildHrTraceSvg(a)
        val histSvg       = buildHistogramSvg(a)
        val zoneRows      = buildZoneRows(a)
        val workoutSec    = buildWorkoutSection(a)
        val restSec       = buildRestSection(a)

        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${e(a.sessionName)}</title>
<style>
:root{
  --bg:#0f1117; --card:#1a1d2e; --border:#2a2d42;
  --text:#e8eaf0; --muted:#7a7d92; --acc:#ff5252;
  --z1:#4CAF50; --z2:#8BC34A; --z3:#FFC107; --z4:#FF5722; --z5:#F44336;
}
*{box-sizing:border-box;margin:0;padding:0}
body{background:var(--bg);color:var(--text);font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;
     padding:20px;max-width:980px;margin:0 auto;line-height:1.5}
h1{font-size:1.7em;font-weight:700;margin-bottom:4px}
h2{font-size:.75em;font-weight:600;letter-spacing:.1em;text-transform:uppercase;
   color:var(--muted);margin-bottom:16px}
.meta{color:var(--muted);font-size:.88em;margin-bottom:28px}
.card{background:var(--card);border:1px solid var(--border);border-radius:12px;
      padding:22px;margin-bottom:18px;overflow:hidden}
.stat-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(145px,1fr));gap:12px}
.stat{background:var(--bg);border-radius:8px;padding:12px 14px}
.sl{font-size:.7em;text-transform:uppercase;letter-spacing:.07em;color:var(--muted);margin-bottom:3px}
.sv{font-size:1.45em;font-weight:700}
.sv.red{color:var(--acc)}
table{width:100%;border-collapse:collapse;font-size:.88em}
th{padding:7px 10px;text-align:left;color:var(--muted);border-bottom:1px solid var(--border);
   font-size:.75em;text-transform:uppercase;letter-spacing:.05em;font-weight:500}
td{padding:9px 10px;border-bottom:1px solid #1f2235}
tr:last-child td{border-bottom:none}
.dot{display:inline-block;width:9px;height:9px;border-radius:50%;margin-right:6px;flex-shrink:0}
svg{width:100%;height:auto;display:block}
.sub{color:var(--muted);font-size:.82em;margin-top:12px}
@media(max-width:520px){.stat-grid{grid-template-columns:1fr 1fr}}
</style>
</head>
<body>

<h1>${e(a.sessionName)}</h1>
<p class="meta">
  ${e(a.sessionDateLabel)}&nbsp;&nbsp;·&nbsp;&nbsp;${fmtDur(a.totalDurationMs)} total
  &nbsp;&nbsp;·&nbsp;&nbsp;${a.readings.size} readings
  &nbsp;&nbsp;·&nbsp;&nbsp;Configured max HR: ${a.zones.maxHr} BPM
</p>

<div class="card">
  <h2>Session Summary</h2>
  <div class="stat-grid">
    <div class="stat"><div class="sl">Duration</div>
      <div class="sv">${fmtDurShort(a.totalDurationMs)}</div></div>
    <div class="stat"><div class="sl">Workout Time</div>
      <div class="sv">${fmtDurShort(a.totalWorkoutMs)}</div></div>
    <div class="stat"><div class="sl">Rest Time</div>
      <div class="sv">${fmtDurShort(a.totalRestMs)}</div></div>
    <div class="stat"><div class="sl">Average HR</div>
      <div class="sv red">${a.avgHr} <span style="font-size:.5em;color:var(--muted)">BPM</span></div></div>
    <div class="stat"><div class="sl">Peak HR</div>
      <div class="sv red">${a.peakHr} <span style="font-size:.5em;color:var(--muted)">BPM</span></div></div>
    <div class="stat"><div class="sl">Est. Resting HR</div>
      <div class="sv">${a.estimatedRestingHr} <span style="font-size:.5em;color:var(--muted)">BPM</span></div></div>
  </div>
</div>

<div class="card">
  <h2>Time in Zone</h2>
  <table>
    <thead><tr><th>Zone</th><th>Range</th><th>Time</th><th>% of Session</th></tr></thead>
    <tbody>$zoneRows</tbody>
  </table>
</div>

<div class="card">
  <h2>Heart Rate Over Time</h2>
  $traceSvg
  <p class="sub">Shaded bands = HR zones. Dark overlay = rest periods.</p>
</div>

<div class="card">
  <h2>Time at Each Heart Rate</h2>
  $histSvg
  <p class="sub">Bar color indicates zone. Width = one BPM bucket.</p>
</div>

$workoutSec
$restSec

</body>
</html>"""
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Zone table
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildZoneRows(a: SessionAnalysis): String = buildString {
        for (zone in ZoneLevel.values()) {
            val ms  = a.overallZoneTimes[zone] ?: 0L
            val pct = pct(ms, a.totalDurationMs)
            append("""<tr>
              <td><span class="dot" style="background:${zone.color}"></span>${e(zone.displayName)}</td>
              <td style="color:var(--muted)">${a.zones.boundsLabel(zone)}</td>
              <td>${fmtDurShort(ms)}</td>
              <td>$pct%</td>
            </tr>""")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Workout segments section
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildWorkoutSection(a: SessionAnalysis): String {
        if (a.workoutSegments.isEmpty()) return ""
        val zoneHeaders = ZoneLevel.values().joinToString("") { "<th>${it.shortName}</th>" }
        val rows = buildString {
            a.workoutSegments.forEach { seg ->
                val zoneTimes = seg.zoneTimeMs(a.zones)
                val zoneCells = ZoneLevel.values().joinToString("") { zone ->
                    val p = pct(zoneTimes[zone] ?: 0L, seg.durationMs)
                    "<td>$p%</td>"
                }
                append("""<tr>
                  <td>${seg.index}</td>
                  <td>${fmtDurShort(seg.durationMs)}</td>
                  <td>${seg.avgHr} BPM</td>
                  <td>${seg.peakHr} BPM</td>
                  $zoneCells
                </tr>""")
            }
        }
        return """
<div class="card">
  <h2>Exercise Periods (${a.workoutSegments.size})</h2>
  <table>
    <thead><tr><th>#</th><th>Duration</th><th>Avg HR</th><th>Peak HR</th>$zoneHeaders</tr></thead>
    <tbody>$rows</tbody>
  </table>
</div>"""
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rest segments section
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildRestSection(a: SessionAnalysis): String {
        if (a.restSegments.isEmpty()) return ""
        val rows = buildString {
            a.restSegments.forEach { seg ->
                val decay = seg.decayRateBpmPerMin
                val decayStr = if (decay > 0.1) String.format("%.1f BPM/min", decay) else "—"
                append("""<tr>
                  <td>${seg.index}</td>
                  <td>${fmtDurShort(seg.durationMs)}</td>
                  <td>${seg.avgHr} BPM</td>
                  <td>$decayStr</td>
                </tr>""")
            }
        }
        return """
<div class="card">
  <h2>Rest Periods (${a.restSegments.size})</h2>
  <table>
    <thead><tr><th>#</th><th>Duration</th><th>Avg HR</th><th>HR Decay</th></tr></thead>
    <tbody>$rows</tbody>
  </table>
  <p class="sub">HR decay = how fast your heart rate drops during rest (higher = better cardiovascular fitness).</p>
</div>"""
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HR-over-time SVG
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildHrTraceSvg(a: SessionAnalysis): String {
        if (a.readings.size < 2) return "<p style='color:var(--muted)'>Not enough data to plot.</p>"

        // Chart geometry
        val svgW = 860; val svgH = 300
        val pL = 52; val pR = 16; val pT = 14; val pB = 52
        val cW = svgW - pL - pR        // chart width  = 792
        val cH = svgH - pT - pB        // chart height = 234

        // Y axis range — always include configured max HR and actual peak
        val yMin = maxOf(35, a.minHr - 12)
        val yMax = maxOf(a.zones.maxHr + 12, a.peakHr + 8)
        val yRange = (yMax - yMin).toDouble()

        val startMs  = a.sessionStartMs
        val totalMs  = a.totalDurationMs.toDouble()

        fun xOf(ms: Long): Double = pL + cW * ((ms - startMs) / totalMs)
        fun yOf(hr: Int):  Double = pT + cH * (yMax - hr) / yRange

        // ── Zone bands ──────────────────────────────────────────────────────
        val bands = buildString {
            for (zone in ZoneLevel.values()) {
                val lo = a.zones.lowerBound(zone).coerceAtLeast(yMin)
                val hi = a.zones.upperBound(zone, yMax).coerceAtMost(yMax)
                if (lo >= hi) continue
                val y1 = yOf(hi); val y2 = yOf(lo)
                append("""<rect x="$pL" y="${f(y1)}" width="$cW" height="${f(y2-y1)}" fill="${zone.color}" opacity="0.20"/>""")
            }
        }

        // ── Rest-period overlays ─────────────────────────────────────────────
        val restOverlays = buildString {
            for (seg in a.restSegments) {
                val x1 = xOf(seg.startMs); val x2 = xOf(seg.endMs)
                val w  = (x2 - x1).coerceAtLeast(1.0)
                append("""<rect x="${f(x1)}" y="$pT" width="${f(w)}" height="$cH" fill="#000" opacity="0.28"/>""")
            }
        }

        // ── HR trace — downsample to ≤ 2000 points ──────────────────────────
        val step   = maxOf(1, a.readings.size / 2000)
        val points = a.readings
            .filterIndexed { i, _ -> i % step == 0 }
            .joinToString(" ") { r -> "${f(xOf(r.timestampMs))},${f(yOf(r.hr))}" }

        // ── Y-axis ticks ─────────────────────────────────────────────────────
        val yTicks = buildString {
            val step10 = if (yRange > 100) 20 else 10
            var v = ((yMin / step10) + 1) * step10
            while (v <= yMax) {
                val y = yOf(v)
                append("""<line x1="${pL-4}" y1="${f(y)}" x2="${pL+cW}" y2="${f(y)}" stroke="#2a2d42" stroke-width="1"/>""")
                append("""<text x="${pL-7}" y="${f(y+4)}" text-anchor="end" fill="#7a7d92" font-size="11">$v</text>""")
                v += step10
            }
        }

        // ── X-axis ticks (every 5 min) ───────────────────────────────────────
        val xTicks = buildString {
            val intv = 5 * 60_000L
            var t = intv
            while (t < a.totalDurationMs) {
                val x = xOf(startMs + t)
                append("""<line x1="${f(x)}" y1="$pT" x2="${f(x)}" y2="${pT+cH+4}" stroke="#2a2d42" stroke-width="1"/>""")
                append("""<text x="${f(x)}" y="${pT+cH+17}" text-anchor="middle" fill="#7a7d92" font-size="11">${t/60_000}m</text>""")
                t += intv
            }
        }

        // ── Zone legend ──────────────────────────────────────────────────────
        val legend = buildString {
            var lx = pL
            for (zone in ZoneLevel.values()) {
                append("""<rect x="$lx" y="${pT+cH+30}" width="10" height="10" fill="${zone.color}" rx="2"/>""")
                append("""<text x="${lx+13}" y="${pT+cH+39}" fill="#7a7d92" font-size="10">${zone.shortName}</text>""")
                lx += 52
            }
        }

        // ── Y-axis label ─────────────────────────────────────────────────────
        val yLabelX = pL - 38
        val yLabelY = pT + cH / 2

        return """<svg viewBox="0 0 $svgW $svgH" xmlns="http://www.w3.org/2000/svg" style="background:#0f1117;border-radius:8px">
  <defs>
    <clipPath id="cc"><rect x="$pL" y="$pT" width="$cW" height="$cH"/></clipPath>
  </defs>
  $bands
  <g clip-path="url(#cc)">
    $restOverlays
    <polyline points="$points" fill="none" stroke="#ff5252" stroke-width="1.8" stroke-linejoin="round"/>
  </g>
  <!-- axes -->
  <line x1="$pL" y1="$pT" x2="$pL" y2="${pT+cH}" stroke="#3a3d52" stroke-width="1"/>
  <line x1="$pL" y1="${pT+cH}" x2="${pL+cW}" y2="${pT+cH}" stroke="#3a3d52" stroke-width="1"/>
  $yTicks
  $xTicks
  $legend
  <text x="$yLabelX" y="$yLabelY" transform="rotate(-90,$yLabelX,$yLabelY)"
        text-anchor="middle" fill="#7a7d92" font-size="11">BPM</text>
</svg>"""
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Histogram SVG
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildHistogramSvg(a: SessionAnalysis): String {
        if (a.histogram.isEmpty()) return "<p style='color:var(--muted)'>Not enough data to plot.</p>"

        val svgW = 860; val svgH = 200
        val pL = 52; val pR = 16; val pT = 10; val pB = 40
        val cW = svgW - pL - pR
        val cH = svgH - pT - pB

        val minHr  = a.histogram.keys.minOrNull() ?: 40
        val maxHr  = a.histogram.keys.maxOrNull() ?: 200
        val hrSpan = maxOf(1, maxHr - minHr + 1)
        val barW   = cW.toDouble() / hrSpan

        val maxMs  = a.histogram.values.maxOrNull() ?: 1L

        fun bx(hr: Int):  Double = pL + (hr - minHr) * barW
        fun by(ms: Long): Double = pT + cH - cH * ms.toDouble() / maxMs

        val bars = buildString {
            for ((hr, ms) in a.histogram.toSortedMap()) {
                val zone = a.zones.zoneFor(hr)
                val x    = bx(hr); val y = by(ms)
                val h    = (pT + cH) - y
                append("""<rect x="${f(x)}" y="${f(y)}" width="${f(maxOf(barW-0.5,0.5))}" height="${f(h)}" fill="${zone.color}" opacity="0.88"/>""")
            }
        }

        // Y-axis ticks in minutes
        val maxMin = maxMs / 60_000.0
        val tickMin = when {
            maxMin > 10 -> 5.0;  maxMin > 2 -> 1.0;  else -> 0.5
        }
        val yTicks = buildString {
            var t = tickMin
            while (t <= maxMin + 0.01) {
                val ms = (t * 60_000).toLong()
                val y  = by(ms)
                append("""<line x1="${pL-4}" y1="${f(y)}" x2="${pL+cW}" y2="${f(y)}" stroke="#2a2d42" stroke-width="1"/>""")
                val lbl = if (t == t.toLong().toDouble()) "${t.toLong()}m" else "${t}m"
                append("""<text x="${pL-7}" y="${f(y+4)}" text-anchor="end" fill="#7a7d92" font-size="11">$lbl</text>""")
                t += tickMin
            }
        }

        // X-axis ticks every 10 BPM
        val xTicks = buildString {
            var hr = ((minHr / 10) + 1) * 10
            while (hr <= maxHr) {
                val x = bx(hr) + barW / 2
                append("""<text x="${f(x)}" y="${pT+cH+17}" text-anchor="middle" fill="#7a7d92" font-size="11">$hr</text>""")
                hr += 10
            }
        }

        val yLabelX = pL - 38
        val yLabelY = pT + cH / 2

        return """<svg viewBox="0 0 $svgW $svgH" xmlns="http://www.w3.org/2000/svg" style="background:#0f1117;border-radius:8px">
  $bars
  <line x1="$pL" y1="$pT" x2="$pL" y2="${pT+cH}" stroke="#3a3d52" stroke-width="1"/>
  <line x1="$pL" y1="${pT+cH}" x2="${pL+cW}" y2="${pT+cH}" stroke="#3a3d52" stroke-width="1"/>
  $yTicks
  $xTicks
  <text x="${pL+cW/2}" y="${svgH-4}" text-anchor="middle" fill="#7a7d92" font-size="11">Heart Rate (BPM)</text>
  <text x="$yLabelX" y="$yLabelY" transform="rotate(-90,$yLabelX,$yLabelY)"
        text-anchor="middle" fill="#7a7d92" font-size="11">Minutes</text>
</svg>"""
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    private fun f(d: Double) = String.format("%.1f", d)
    private fun e(s: String) = s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    private fun pct(part: Long, total: Long) = if (total > 0) (part * 100L / total).toInt() else 0

    private fun fmtDurShort(ms: Long): String {
        val h = ms / 3_600_000L
        val m = (ms % 3_600_000L) / 60_000L
        val s = (ms % 60_000L) / 1_000L
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun fmtDur(ms: Long): String {
        val h = ms / 3_600_000L
        val m = (ms % 3_600_000L) / 60_000L
        return when {
            h > 0  -> "${h}h ${m}min"
            m > 0  -> "${m}min"
            else   -> "${ms / 1000}s"
        }
    }
}
