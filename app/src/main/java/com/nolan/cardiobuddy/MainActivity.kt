package com.nolan.cardiobuddy

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var deviceNameText:    TextView
    private lateinit var changeDeviceBtn:   Button
    private lateinit var maxHrValueText:    TextView
    private lateinit var liveHrText:        TextView
    private lateinit var bpmLabel:          TextView
    private lateinit var statusText:        TextView
    private lateinit var recordButton:      Button
    private lateinit var endButton:         Button

    // ── BLE scan state ────────────────────────────────────────────────────────
    private val foundDevices    = mutableListOf<BluetoothDevice>()
    private val foundNames      = mutableListOf<String>()
    private var scanning        = false
    private val scanHandler     = Handler(Looper.getMainLooper())

    private val btAdapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val bleScanner get() = btAdapter?.bluetoothLeScanner

    // ── Service receiver ──────────────────────────────────────────────────────
    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                HeartRateService.ACTION_HR_UPDATE -> {
                    val hr = intent.getIntExtra(HeartRateService.EXTRA_HR, 0)
                    liveHrText.text   = hr.toString()
                    liveHrText.visibility = View.VISIBLE
                    bpmLabel.visibility   = View.VISIBLE
                    statusText.text   = "Recording…"
                }
                HeartRateService.ACTION_SESSION_COMPLETE -> {
                    onSessionComplete(
                        intent.getStringExtra(HeartRateService.EXTRA_SESSION_FILE) ?: "",
                        intent.getIntExtra(HeartRateService.EXTRA_AVG_HR,       0),
                        intent.getIntExtra(HeartRateService.EXTRA_PEAK_HR,      0),
                        intent.getLongExtra(HeartRateService.EXTRA_DURATION,    0L),
                        intent.getIntExtra(HeartRateService.EXTRA_SAMPLE_COUNT, 0)
                    )
                }
                HeartRateService.ACTION_CONNECTION_FAILED -> {
                    showIdleUi()
                    statusText.text = "Connection failed — check device is on and in range."
                }
            }
        }
    }

    companion object { private const val PERM_REQ = 100; private const val SCAN_MS = 15_000L }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        deviceNameText  = findViewById(R.id.deviceNameText)
        changeDeviceBtn = findViewById(R.id.changeDeviceBtn)
        maxHrValueText  = findViewById(R.id.maxHrValueText)
        liveHrText      = findViewById(R.id.liveHrText)
        bpmLabel        = findViewById(R.id.bpmLabel)
        statusText      = findViewById(R.id.statusText)
        recordButton    = findViewById(R.id.recordButton)
        endButton       = findViewById(R.id.endButton)

        changeDeviceBtn.setOnClickListener         { startScan() }
        findViewById<View>(R.id.editMaxHrBtn).setOnClickListener { showMaxHrDialog() }
        recordButton.setOnClickListener             { onRecordTapped() }
        endButton.setOnClickListener               { onEndTapped() }

        checkPermissions()
        refreshHeader()
        showIdleUi()
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(serviceReceiver, IntentFilter().apply {
            addAction(HeartRateService.ACTION_HR_UPDATE)
            addAction(HeartRateService.ACTION_SESSION_COMPLETE)
            addAction(HeartRateService.ACTION_CONNECTION_FAILED)
        })
        refreshHeader()
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(serviceReceiver)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permissions
    // ─────────────────────────────────────────────────────────────────────────

    private fun checkPermissions() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty())
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERM_REQ)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Record flow
    // ─────────────────────────────────────────────────────────────────────────

    private fun onRecordTapped() {
        val address = SettingsManager.getDeviceAddress(this)
        if (address == null) {
            Toast.makeText(this, "Scan and select a device first.", Toast.LENGTH_SHORT).show()
            startScan(); return
        }
        val maxHr = SettingsManager.getMaxHr(this)
        if (maxHr <= 0) {
            showMaxHrDialog { showRestReminder { startRecording(address) } }
            return
        }
        showRestReminder { startRecording(address) }
    }

    private fun showRestReminder(onStart: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Before You Begin")
            .setMessage(
                "For the most accurate analysis:\n\n" +
                "• Let the app record 1–2 minutes while at rest before starting your workout.\n" +
                "• After finishing, cool down for 1–2 minutes before tapping End.\n\n" +
                "This lets the app establish your resting heart rate and calculate recovery data."
            )
            .setPositiveButton("Start Recording") { _, _ -> onStart() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startRecording(address: String) {
        statusText.text = "Connecting…"
        showRecordingUi()
        ContextCompat.startForegroundService(this,
            Intent(this, HeartRateService::class.java).apply {
                action = HeartRateService.ACTION_START_RECORDING
                putExtra(HeartRateService.EXTRA_DEVICE_ADDRESS, address)
            }
        )
    }

    private fun onEndTapped() {
        startService(Intent(this, HeartRateService::class.java).apply {
            action = HeartRateService.ACTION_STOP_RECORDING
        })
        statusText.text = "Finishing session…"
        endButton.isEnabled = false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Session complete → name prompt → SummaryActivity
    // ─────────────────────────────────────────────────────────────────────────

    private fun onSessionComplete(
        filePath: String, avgHr: Int, peakHr: Int, duration: Long, samples: Int
    ) {
        showIdleUi()
        val defaultName = "Heart Rate Recording " +
            SimpleDateFormat("yyMMdd", Locale.US).format(Date())

        val input = EditText(this).apply {
            setText(defaultName); selectAll()
            setPadding(52, 28, 52, 8)
        }
        AlertDialog.Builder(this)
            .setTitle("Name This Session")
            .setView(input)
            .setPositiveButton("Save & Analyze") { _, _ ->
                val name = input.text.toString().trim().ifBlank { defaultName }
                startActivity(Intent(this, SummaryActivity::class.java).apply {
                    putExtra(HeartRateService.EXTRA_SESSION_FILE, filePath)
                    putExtra(HeartRateService.EXTRA_AVG_HR,       avgHr)
                    putExtra(HeartRateService.EXTRA_PEAK_HR,      peakHr)
                    putExtra(HeartRateService.EXTRA_DURATION,     duration)
                    putExtra(HeartRateService.EXTRA_SAMPLE_COUNT, samples)
                    putExtra(SummaryActivity.EXTRA_SESSION_NAME,  name)
                })
            }
            .setCancelable(false)
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Device scanning
    // ─────────────────────────────────────────────────────────────────────────

    private fun startScan() {
        if (btAdapter?.isEnabled != true) {
            Toast.makeText(this, "Please enable Bluetooth first.", Toast.LENGTH_SHORT).show(); return
        }
        foundDevices.clear(); foundNames.clear()
        scanning = true
        statusText.text = "Scanning for devices… (15 s)"

        @SuppressLint("MissingPermission")
        fun doScan() = bleScanner?.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback
        )
        doScan()

        scanHandler.postDelayed({ stopScan(); showScanDialog() }, SCAN_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (scanning) { bleScanner?.stopScan(scanCallback); scanning = false }
        scanHandler.removeCallbacksAndMessages(null)
        statusText.text = "Ready"
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(t: Int, r: ScanResult) {
            val d = r.device
            if (foundDevices.none { it.address == d.address }) {
                foundDevices.add(d)
                val name = try { d.name } catch (_: SecurityException) { null }
                    ?: "Unknown (…${d.address.takeLast(5)})"
                foundNames.add(name)
            }
        }
        override fun onScanFailed(e: Int) {
            runOnUiThread { statusText.text = "Scan failed (code $e)." }
        }
    }

    private fun showScanDialog() {
        if (foundDevices.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No Devices Found")
                .setMessage("Make sure your heart rate monitor is awake and in range, then try again.")
                .setPositiveButton("Retry") { _, _ -> startScan() }
                .setNegativeButton("Cancel", null).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Select Device")
            .setItems(foundNames.toTypedArray()) { _, i ->
                val address = foundDevices[i].address
                val name    = foundNames[i]
                SettingsManager.saveDevice(this, address, name)
                refreshHeader()
                statusText.text = "Device saved: $name. Tap Record when ready."
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Max HR dialog
    // ─────────────────────────────────────────────────────────────────────────

    private fun showMaxHrDialog(onSaved: (() -> Unit)? = null) {
        val current = SettingsManager.getMaxHr(this)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "e.g. 185"
            if (current > 0) setText(current.toString())
            setPadding(52, 28, 52, 8)
        }
        AlertDialog.Builder(this)
            .setTitle("Max Heart Rate")
            .setMessage("Enter your maximum HR in BPM. If unsure, use 220 minus your age as a starting estimate — you can update it as you learn your real ceiling from hard efforts.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val v = input.text.toString().toIntOrNull()
                if (v != null && v in 100..220) {
                    SettingsManager.setMaxHr(this, v)
                    refreshHeader()
                    onSaved?.invoke()
                } else {
                    Toast.makeText(this, "Enter a value between 100 and 220.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun refreshHeader() {
        val device = SettingsManager.getDeviceName(this)
        val maxHr  = SettingsManager.getMaxHr(this)
        deviceNameText.text  = device ?: "No device selected"
        changeDeviceBtn.text = if (device != null) "Change" else "Scan"
        maxHrValueText.text  = if (maxHr > 0) "$maxHr BPM" else "Not set — tap Edit"
    }

    private fun showIdleUi() {
        recordButton.visibility = View.VISIBLE; recordButton.isEnabled = true
        endButton.visibility    = View.GONE
        liveHrText.visibility   = View.GONE
        bpmLabel.visibility     = View.GONE
        statusText.text         = "Ready"
    }

    private fun showRecordingUi() {
        recordButton.visibility = View.GONE
        endButton.visibility    = View.VISIBLE; endButton.isEnabled = true
    }
}
