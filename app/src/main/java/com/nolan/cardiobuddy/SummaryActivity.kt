package com.nolan.cardiobuddy

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

class SummaryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SESSION_NAME = "session_name"
    }

    // ── Views ──────────────────────────────────────────────────────────────────
    private lateinit var loadingView:       View
    private lateinit var contentScroll:     View
    private lateinit var sessionTitleText:  TextView
    private lateinit var sessionDateText:   TextView
    private lateinit var durationText:      TextView
    private lateinit var workoutRestText:   TextView
    private lateinit var avgHrText:         TextView
    private lateinit var peakHrText:        TextView
    private lateinit var restingHrText:     TextView
    private lateinit var zoneContainer:     LinearLayout
    private lateinit var segmentContainer:  LinearLayout
    private lateinit var shareReportBtn:    Button
    private lateinit var shareCsvBtn:       Button

    // ── Data ───────────────────────────────────────────────────────────────────
    private var csvFile:    File? = null
    private var reportFile: File? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_summary)

        loadingView      = findViewById(R.id.loadingView)
        contentScroll    = findViewById(R.id.contentScroll)
        sessionTitleText = findViewById(R.id.sessionTitleText)
        sessionDateText  = findViewById(R.id.sessionDateText)
        durationText     = findViewById(R.id.durationText)
        workoutRestText  = findViewById(R.id.workoutRestText)
        avgHrText        = findViewById(R.id.avgHrText)
        peakHrText       = findViewById(R.id.peakHrText)
        restingHrText    = findViewById(R.id.restingHrText)
        zoneContainer    = findViewById(R.id.zoneContainer)
        segmentContainer = findViewById(R.id.segmentContainer)
        shareReportBtn   = findViewById(R.id.shareReportBtn)
        shareCsvBtn      = findViewById(R.id.shareCsvBtn)

        shareReportBtn.setOnClickListener { shareFile(reportFile, "text/html") }
        shareCsvBtn.setOnClickListener    { shareFile(csvFile,    "text/csv")  }
        findViewById<Button>(R.id.doneBtn).setOnClickListener { finish() }

        showLoading()
        runAnalysis()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Analysis (off the main thread)
    // ─────────────────────────────────────────────────────────────────────────

    private fun runAnalysis() {
        val filePath    = intent.getStringExtra(HeartRateService.EXTRA_SESSION_FILE) ?: ""
        val sessionName = intent.getStringExtra(EXTRA_SESSION_NAME) ?: "Session"
        val maxHr       = SettingsManager.getMaxHr(this)

        csvFile = File(filePath)

        Thread {
            val analysis = if (csvFile!!.exists() && maxHr > 0)
                SessionAnalyzer.analyzeFromCsv(csvFile!!, maxHr, sessionName)
            else null

            val report = analysis?.let {
                runCatching { ReportGenerator.generate(it, getExternalFilesDir(null) ?: filesDir) }
                    .getOrNull()
            }

            runOnUiThread {
                reportFile = report
                if (analysis != null) populateUi(analysis)
                else showFallback()
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Populate UI
    // ─────────────────────────────────────────────────────────────────────────

    private fun populateUi(a: SessionAnalysis) {
        showContent()

        sessionTitleText.text = a.sessionName
        sessionDateText.text  = a.sessionDateLabel
        durationText.text     = "Total: ${fmt(a.totalDurationMs)}"
        workoutRestText.text  = "Workout: ${fmt(a.totalWorkoutMs)}  ·  Rest: ${fmt(a.totalRestMs)}"
        avgHrText.text        = "Avg HR: ${a.avgHr} BPM"
        peakHrText.text       = "Peak HR: ${a.peakHr} BPM"
        restingHrText.text    = "Est. Resting HR: ${a.estimatedRestingHr} BPM"

        // ── Zone rows ────────────────────────────────────────────────────────
        zoneContainer.removeAllViews()
        for (zone in ZoneLevel.values()) {
            val ms  = a.overallZoneTimes[zone] ?: 0L
            val pct = if (a.totalDurationMs > 0) (ms * 100L / a.totalDurationMs).toInt() else 0

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(22, 22).also { it.marginEnd = 14; it.topMargin = 2 }
                background = getDrawable(android.R.drawable.presence_online) // circle placeholder
                setBackgroundColor(Color.parseColor(zone.color))
                // Make it a circle via a simple rounding - good enough for a dot
            }
            val label = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = zone.displayName
                setTextColor(Color.parseColor("#e8eaf0"))
                textSize = 14f
            }
            val value = TextView(this).apply {
                text = "${fmt(ms)} ($pct%)"
                setTextColor(Color.parseColor("#7a7d92"))
                textSize = 14f
            }
            row.addView(dot); row.addView(label); row.addView(value)
            zoneContainer.addView(row)

            // Divider
            zoneContainer.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).also { it.topMargin = 2; it.bottomMargin = 2 }
                setBackgroundColor(Color.parseColor("#1f2235"))
            })
        }

        // ── Segment summary ──────────────────────────────────────────────────
        segmentContainer.removeAllViews()

        if (a.workoutSegments.isNotEmpty()) {
            addSectionHeader("Exercise Periods (${a.workoutSegments.size})")
            a.workoutSegments.forEach { seg ->
                val zoneTimes = seg.zoneTimeMs(a.zones)
                val topZone = ZoneLevel.values().maxByOrNull { zoneTimes[it] ?: 0L }
                val detail = buildString {
                    append("${fmt(seg.durationMs)}  ·  avg ${seg.avgHr} BPM  ·  peak ${seg.peakHr} BPM")
                    if (topZone != null) {
                        val p = if (seg.durationMs > 0) ((zoneTimes[topZone]!! * 100L) / seg.durationMs).toInt() else 0
                        append("\nMostly in ${topZone.displayName} ($p%)")
                    }
                }
                addSegmentRow("#${seg.index}", detail, "#ff5252")
            }
        }

        if (a.restSegments.isNotEmpty()) {
            addSectionHeader("Rest Periods (${a.restSegments.size})")
            a.restSegments.forEach { seg ->
                val decay = seg.decayRateBpmPerMin
                val detail = buildString {
                    append("${fmt(seg.durationMs)}  ·  avg ${seg.avgHr} BPM")
                    if (decay > 0.1) append("\nHR decay: ${"%.1f".format(decay)} BPM/min")
                }
                addSegmentRow("#${seg.index}", detail, "#8BC34A")
            }
        }

        shareReportBtn.isEnabled = reportFile != null
    }

    private fun addSectionHeader(text: String) {
        segmentContainer.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#7a7d92"))
            textSize = 11f
            setPadding(0, 20, 0, 6)
            letterSpacing = 0.08f
            isAllCaps = true
        })
    }

    private fun addSegmentRow(badge: String, detail: String, badgeColor: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 10)
        }
        row.addView(TextView(this).apply {
            text = badge
            setTextColor(Color.parseColor(badgeColor))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(80, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 12, 0)
        })
        row.addView(TextView(this).apply {
            text = detail
            setTextColor(Color.parseColor("#e8eaf0"))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        segmentContainer.addView(row)
        segmentContainer.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#1f2235"))
        })
    }

    private fun showFallback() {
        showContent()
        sessionTitleText.text = "Session Complete"
        sessionDateText.text  = ""
        durationText.text     = "Detailed analysis unavailable."
        workoutRestText.text  = if (SettingsManager.getMaxHr(this) <= 0)
            "Set Max HR in settings and re-record for full analysis."
        else "No readable CSV data found."
        avgHrText.text   = ""
        peakHrText.text  = ""
        restingHrText.text = ""
        shareReportBtn.isEnabled = false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // File sharing
    // ─────────────────────────────────────────────────────────────────────────

    private fun shareFile(file: File?, mime: String) {
        val f = file ?: run {
            Toast.makeText(this, "File not available.", Toast.LENGTH_SHORT).show(); return
        }
        if (!f.exists()) {
            Toast.makeText(this, "File not found: ${f.name}", Toast.LENGTH_SHORT).show(); return
        }
        val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", f)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, f.nameWithoutExtension)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Export"
        ))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Visibility helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun showLoading()  { loadingView.visibility = View.VISIBLE;  contentScroll.visibility = View.GONE }
    private fun showContent()  { loadingView.visibility = View.GONE;     contentScroll.visibility = View.VISIBLE }

    // ─────────────────────────────────────────────────────────────────────────
    // Duration formatter
    // ─────────────────────────────────────────────────────────────────────────

    private fun fmt(ms: Long): String {
        val h = ms / 3_600_000L
        val m = (ms % 3_600_000L) / 60_000L
        val s = (ms % 60_000L) / 1_000L
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
