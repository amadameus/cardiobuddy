package com.nolan.cardiobuddy

import android.content.Context

object SettingsManager {

    private const val PREFS = "hr_monitor_prefs"

    private const val KEY_MAX_HR         = "max_hr"
    private const val KEY_DEVICE_ADDRESS = "device_address"
    private const val KEY_DEVICE_NAME    = "device_name"

    // ── Max HR ────────────────────────────────────────────────────────────────

    fun getMaxHr(ctx: Context): Int =
        prefs(ctx).getInt(KEY_MAX_HR, 0)

    fun setMaxHr(ctx: Context, maxHr: Int) =
        prefs(ctx).edit().putInt(KEY_MAX_HR, maxHr).apply()

    // ── Device ────────────────────────────────────────────────────────────────

    fun getDeviceAddress(ctx: Context): String? =
        prefs(ctx).getString(KEY_DEVICE_ADDRESS, null)

    fun getDeviceName(ctx: Context): String? =
        prefs(ctx).getString(KEY_DEVICE_NAME, null)

    fun saveDevice(ctx: Context, address: String, name: String) =
        prefs(ctx).edit()
            .putString(KEY_DEVICE_ADDRESS, address)
            .putString(KEY_DEVICE_NAME, name)
            .apply()

    fun clearDevice(ctx: Context) =
        prefs(ctx).edit()
            .remove(KEY_DEVICE_ADDRESS)
            .remove(KEY_DEVICE_NAME)
            .apply()

    // ── Private ───────────────────────────────────────────────────────────────

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
