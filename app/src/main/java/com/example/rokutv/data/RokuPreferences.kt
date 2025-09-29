package com.example.rokutv.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RokuPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("roku_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    // ---------- Devices (persist ip + name) ----------
    fun getDevices(): List<Device> {
        val json = prefs.getString("devices_json", "[]") ?: "[]"
        val type = object : TypeToken<List<Device>>() {}.type
        return gson.fromJson<List<Device>>(json, type) ?: emptyList()
    }

    fun saveDevices(devices: List<Device>) {
        val json = gson.toJson(devices)
        prefs.edit().putString("devices_json", json).apply()
    }

    fun renameDevice(ip: String, newName: String) {
        val current = getDevices().toMutableList()
        val idx = current.indexOfFirst { it.ip == ip }
        if (idx >= 0) {
            current[idx] = current[idx].copy(name = newName)
            saveDevices(current)
        }
    }

    fun getSelectedIp(): String? = prefs.getString("selected_ip", null)
    fun saveSelectedIp(ip: String?) = prefs.edit().putString("selected_ip", ip).apply()

    // ---------- App / interval ----------
    fun getSelectedApp(): String? = prefs.getString("selected_app", null)
    fun saveSelectedApp(app: String) = prefs.edit().putString("selected_app", app).apply()

    fun getIntervalSeconds(): Int = prefs.getInt("interval", 30)
    fun saveIntervalSeconds(seconds: Int) = prefs.edit().putInt("interval", seconds).apply()

    fun getAutoRelaunchEnabled(): Boolean = prefs.getBoolean("auto_relaunch_enabled", false)
    fun setAutoRelaunchEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("auto_relaunch_enabled", enabled).apply()

    // ---------- Schedules ----------
    fun isScheduleOnEnabled(): Boolean = prefs.getBoolean("sched_on_enabled", false)
    fun setScheduleOnEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("sched_on_enabled", enabled).apply()

    fun isScheduleOffEnabled(): Boolean = prefs.getBoolean("sched_off_enabled", false)
    fun setScheduleOffEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("sched_off_enabled", enabled).apply()

    fun getOnTimeLabel(): String? = prefs.getString("on_time_label", null)
    fun saveOnTimeLabel(label: String?) = prefs.edit().putString("on_time_label", label).apply()

    fun getOffTimeLabel(): String? = prefs.getString("off_time_label", null)
    fun saveOffTimeLabel(label: String?) = prefs.edit().putString("off_time_label", label).apply()

    // ---------- Auto Relaunch Suppression (per IP) ----------
    private fun suppressed(): MutableSet<String> =
        prefs.getStringSet("suppressed_ips", emptySet())?.toMutableSet() ?: mutableSetOf()

    fun isSuppressed(ip: String): Boolean = suppressed().contains(ip)
    fun suppressIp(ip: String) {
        val s = suppressed(); s.add(ip)
        prefs.edit().putStringSet("suppressed_ips", s).apply()
    }
    fun clearSuppression(ip: String) {
        val s = suppressed()
        if (s.remove(ip)) prefs.edit().putStringSet("suppressed_ips", s).apply()
    }

    // ---------- Event Log (stored newest first) ----------
    private val LOG_KEY = "event_log"

    fun getLogs(): List<String> {
        val raw = prefs.getString(LOG_KEY, "") ?: ""
        return raw.split('\n').filter { it.isNotBlank() }
    }

    fun clearLogs() {
        prefs.edit().remove(LOG_KEY).apply()
    }

    fun appendLog(line: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$ts] $line"
        val current = getLogs()
        val limited = (listOf(entry) + current).take(300)
        prefs.edit().putString(LOG_KEY, limited.joinToString("\n")).apply()
    }
}
