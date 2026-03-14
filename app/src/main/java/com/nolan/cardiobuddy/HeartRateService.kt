package com.nolan.cardiobuddy

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*

class HeartRateService : Service() {

    companion object {
        const val CHANNEL_ID      = "hr_monitor_channel"
        const val NOTIFICATION_ID = 1

        // Broadcasts
        const val ACTION_HR_UPDATE          = "com.nolan.cardiobuddy.HR_UPDATE"
        const val ACTION_SESSION_COMPLETE   = "com.nolan.cardiobuddy.SESSION_COMPLETE"
        const val ACTION_CONNECTION_FAILED  = "com.nolan.cardiobuddy.CONNECTION_FAILED"

        // Extras
        const val EXTRA_HR           = "heart_rate"
        const val EXTRA_SESSION_FILE = "session_file"
        const val EXTRA_AVG_HR       = "avg_hr"
        const val EXTRA_PEAK_HR      = "peak_hr"
        const val EXTRA_DURATION     = "duration_ms"
        const val EXTRA_SAMPLE_COUNT = "sample_count"

        // Commands
        const val ACTION_START_RECORDING = "com.nolan.cardiobuddy.START_RECORDING"
        const val ACTION_STOP_RECORDING  = "com.nolan.cardiobuddy.STOP_RECORDING"
        const val EXTRA_DEVICE_ADDRESS   = "device_address"

        // Standard BLE Heart Rate Profile
        val HR_SERVICE_UUID:        UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CLIENT_CONFIG_UUID:     UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val TAG = "HeartRateService"
        private const val WAKELOCK_TIMEOUT_MS = 8 * 60 * 60 * 1000L
    }

    private var bluetoothGatt:   BluetoothGatt? = null
    private var csvWriter:        PrintWriter?   = null
    private var sessionFile:      File?          = null
    private var isRecording       = false
    private var connected         = false
    private var sessionStartTime  = 0L
    private val hrReadings        = mutableListOf<Int>()
    private var wakeLock:         PowerManager.WakeLock? = null

    private val isoFmt  = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
    private val fileFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    inner class LocalBinder : Binder() { fun getService() = this@HeartRateService }
    private val binder = LocalBinder()

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate()              { super.onCreate(); createNotificationChannel(); acquireWakeLock() }
    override fun onBind(i: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> {
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS) ?: return START_NOT_STICKY
                startForeground(NOTIFICATION_ID, buildNotification("Connecting…", -1))
                connectToDevice(address)
            }
            ACTION_STOP_RECORDING -> finishSession()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { wakeLock?.let { if (it.isHeld) it.release() }; super.onDestroy() }

    // ─────────────────────────────────────────────────────────────────────────
    // BLE
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun connectToDevice(address: String) {
        val device = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter.getRemoteDevice(address)
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connected = true
                    updateNotification("Connected — reading HR service…", -1)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (isRecording) finishSession()
                    else { broadcastConnectionFailed(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val hrChar = if (status == BluetoothGatt.GATT_SUCCESS)
                gatt.getService(HR_SERVICE_UUID)?.getCharacteristic(HR_CHARACTERISTIC_UUID)
            else null

            if (hrChar == null) {
                broadcastConnectionFailed(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
            }

            // Enable notifications
            gatt.setCharacteristicNotification(hrChar, true)
            val desc = hrChar.getDescriptor(CLIENT_CONFIG_UUID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION") desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION") gatt.writeDescriptor(desc)
            }

            startCsvSession()
            isRecording      = true
            sessionStartTime = System.currentTimeMillis()
            updateNotification("Recording…", -1)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) = handleHrValue(value)

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) handleHrValue(characteristic.value)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HR handling
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleHrValue(value: ByteArray) {
        if (value.isEmpty()) return
        val flags = value[0].toInt() and 0xFF
        val hr = if (flags and 0x01 == 0) value[1].toInt() and 0xFF
                 else (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
        val now = System.currentTimeMillis()

        if (isRecording) {
            csvWriter?.println("$now,${isoFmt.format(Date(now))},$hr")
            csvWriter?.flush()
            hrReadings.add(hr)
        }

        LocalBroadcastManager.getInstance(this)
            .sendBroadcast(Intent(ACTION_HR_UPDATE).putExtra(EXTRA_HR, hr))

        val elapsed = (now - sessionStartTime) / 60_000L
        updateNotification("Recording · ${elapsed}min", hr)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CSV / session
    // ─────────────────────────────────────────────────────────────────────────

    private fun startCsvSession() {
        val dir = getExternalFilesDir(null) ?: filesDir
        sessionFile = File(dir, "hr_session_${fileFmt.format(Date())}.csv")
        csvWriter   = PrintWriter(FileWriter(sessionFile))
        csvWriter?.println("timestamp_ms,timestamp_iso,heart_rate_bpm")
        csvWriter?.flush()
    }

    @SuppressLint("MissingPermission")
    private fun finishSession() {
        isRecording = false
        csvWriter?.flush(); csvWriter?.close(); csvWriter = null
        bluetoothGatt?.disconnect(); bluetoothGatt?.close(); bluetoothGatt = null

        val duration = System.currentTimeMillis() - sessionStartTime
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(ACTION_SESSION_COMPLETE).apply {
                putExtra(EXTRA_SESSION_FILE, sessionFile?.absolutePath ?: "")
                putExtra(EXTRA_AVG_HR,       if (hrReadings.isNotEmpty()) hrReadings.average().toInt() else 0)
                putExtra(EXTRA_PEAK_HR,      hrReadings.maxOrNull() ?: 0)
                putExtra(EXTRA_DURATION,     duration)
                putExtra(EXTRA_SAMPLE_COUNT, hrReadings.size)
            }
        )
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification / wakelock
    // ─────────────────────────────────────────────────────────────────────────

    private fun broadcastConnectionFailed() =
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_CONNECTION_FAILED))

    private fun createNotificationChannel() =
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Heart Rate Monitor", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Recording status"; setShowBadge(false) }
        )

    private fun buildNotification(status: String, hr: Int): Notification {
        val main = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0,
            Intent(this, HeartRateService::class.java).apply { action = ACTION_STOP_RECORDING },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (hr > 0) "HR Monitor · $hr BPM" else "HR Monitor")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(main)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(status: String, hr: Int) =
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status, hr))

    private fun acquireWakeLock() {
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HRMonitor::WakeLock")
            .also { it.acquire(WAKELOCK_TIMEOUT_MS) }
    }
}
