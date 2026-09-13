package com.piprapay.liteforwarder

import android.content.Context
import android.content.SharedPreferences

// All app settings live here (not a server round-trip) so the app keeps
// working the instant it's launched, even before it ever reaches the
// internet. Pairing (device token) and the allowed-number whitelist are
// the only things that matter for whether an SMS is forwarded.
object Prefs {
    private const val FILE = "piprapay_lite_prefs"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_DEVICE_TOKEN = "device_token"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_ALLOWED_NUMBERS = "allowed_numbers" // comma-separated
    private const val KEY_SERVICE_ENABLED = "service_enabled"

    private fun sp(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getBaseUrl(context: Context): String =
        sp(context).getString(KEY_BASE_URL, "") ?: ""

    fun setBaseUrl(context: Context, url: String) {
        sp(context).edit().putString(KEY_BASE_URL, url.trim().trimEnd('/')).apply()
    }

    fun getDeviceToken(context: Context): String? = sp(context).getString(KEY_DEVICE_TOKEN, null)

    fun setDeviceToken(context: Context, token: String?) {
        sp(context).edit().putString(KEY_DEVICE_TOKEN, token).apply()
    }

    fun getDeviceName(context: Context): String? = sp(context).getString(KEY_DEVICE_NAME, null)

    fun setDeviceName(context: Context, name: String?) {
        sp(context).edit().putString(KEY_DEVICE_NAME, name).apply()
    }

    fun isPaired(context: Context): Boolean = !getDeviceToken(context).isNullOrBlank()

    // Empty list = "smart mode": auto-detect any SMS that looks like a
    // bKash/Nagad transaction, from any sender. Non-empty = strict
    // whitelist: only these exact sender addresses are ever logged, no
    // matter what the message says. This is the "admin restricts to
    // allowed numbers only" mode.
    fun getAllowedNumbers(context: Context): List<String> {
        val raw = sp(context).getString(KEY_ALLOWED_NUMBERS, "") ?: ""
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun setAllowedNumbers(context: Context, numbers: List<String>) {
        sp(context).edit().putString(KEY_ALLOWED_NUMBERS, numbers.joinToString(",")).apply()
    }

    fun addAllowedNumber(context: Context, number: String) {
        val n = number.trim()
        if (n.isEmpty()) return
        val current = getAllowedNumbers(context).toMutableList()
        if (!current.contains(n)) {
            current.add(n)
            setAllowedNumbers(context, current)
        }
    }

    fun removeAllowedNumber(context: Context, number: String) {
        val current = getAllowedNumbers(context).toMutableList()
        current.remove(number)
        setAllowedNumbers(context, current)
    }

    fun isServiceEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_SERVICE_ENABLED, true)

    fun setServiceEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
    }
}
