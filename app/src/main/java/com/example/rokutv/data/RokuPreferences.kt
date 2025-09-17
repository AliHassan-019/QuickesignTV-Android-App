package com.example.rokutv.data

import android.content.Context

class RokuPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("roku_prefs", Context.MODE_PRIVATE)

    fun getIpList(): Set<String> = prefs.getStringSet("ips", emptySet()) ?: emptySet()
    fun saveIpList(ips: Set<String>) = prefs.edit().putStringSet("ips", ips).apply()

    fun getSelectedIp(): String? = prefs.getString("selected_ip", null)
    fun saveSelectedIp(ip: String?) = prefs.edit().putString("selected_ip", ip).apply()

    fun getSelectedApp(): String? = prefs.getString("selected_app", null)
    fun saveSelectedApp(app: String) = prefs.edit().putString("selected_app", app).apply()

    fun getIntervalSeconds(): Int = prefs.getInt("interval", 30)
    fun saveIntervalSeconds(seconds: Int) = prefs.edit().putInt("interval", seconds).apply()

    // Auto relaunch
    fun getAutoRelaunchEnabled(): Boolean = prefs.getBoolean("auto_relaunch_enabled", false)
    fun setAutoRelaunchEnabled(enabled: Boolean) = prefs.edit().putBoolean("auto_relaunch_enabled", enabled).apply()

    // Schedules
    fun isScheduleOnEnabled(): Boolean = prefs.getBoolean("schedule_on_enabled", false)
    fun setScheduleOnEnabled(enabled: Boolean) = prefs.edit().putBoolean("schedule_on_enabled", enabled).apply()
    fun isScheduleOffEnabled(): Boolean = prefs.getBoolean("schedule_off_enabled", false)
    fun setScheduleOffEnabled(enabled: Boolean) = prefs.edit().putBoolean("schedule_off_enabled", enabled).apply()

    fun getOnTimeLabel(): String? = prefs.getString("on_time_label", null)
    fun saveOnTimeLabel(label: String?) = prefs.edit().putString("on_time_label", label).apply()

    fun getOffTimeLabel(): String? = prefs.getString("off_time_label", null)
    fun saveOffTimeLabel(label: String?) = prefs.edit().putString("off_time_label", label).apply()
}
