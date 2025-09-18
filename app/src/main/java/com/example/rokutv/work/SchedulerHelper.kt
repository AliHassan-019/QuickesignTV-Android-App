package com.example.rokutv.work

import android.content.Context
import androidx.work.*
import java.util.Calendar
import java.util.concurrent.TimeUnit

object SchedulerHelper {

    private fun ipKey(ip: String) = ip.replace('.', '_')

    // ---------- DAILY (single IP) ----------
    fun scheduleDaily(context: Context, type: String, hour: Int, minute: Int, ip: String) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(Calendar.getInstance())) add(Calendar.DAY_OF_YEAR, 1)
        }
        val delay = cal.timeInMillis - System.currentTimeMillis()

        val command = if (type == "ON") "keypress/PowerOn" else "keypress/PowerOff"
        val tagType = if (type == "ON") "dailyOn" else "dailyOff"
        val unique = "${tagType}_${ipKey(ip)}"

        val data = workDataOf(
            "command" to command,
            "ip" to ip,
            "type" to type,
            "hour" to hour,
            "minute" to minute,
            "repeatDaily" to true
        )

        val request = OneTimeWorkRequestBuilder<RokuWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .addTag(tagType)     // cancel all by tag
            .addTag(unique)      // per-IP unique
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            unique,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun scheduleDailyForAll(context: Context, type: String, hour: Int, minute: Int, ips: List<String>) {
        ips.forEach { ip ->
            if (ip.isNotBlank()) scheduleDaily(context, type, hour, minute, ip)
        }
    }

    fun cancelDailyAll(context: Context, type: String) {
        val tagType = if (type == "ON") "dailyOn" else "dailyOff"
        WorkManager.getInstance(context).cancelAllWorkByTag(tagType)
    }

    // ---------- RELAUNCH (single IP) ----------
    fun scheduleRelaunch(context: Context, intervalSec: Int, ip: String, appId: String) {
        val unique = "relaunch_${ipKey(ip)}"

        val data = workDataOf(
            "command" to "launch/$appId",
            "ip" to ip,
            "relaunch" to true,
            "relaunchIntervalSec" to intervalSec,
            "appId" to appId
        )

        val request = OneTimeWorkRequestBuilder<RokuWorker>()
            .setInitialDelay(intervalSec.toLong(), TimeUnit.SECONDS)
            .setInputData(data)
            .addTag("relaunch")
            .addTag(unique)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            unique,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun scheduleRelaunchForAll(context: Context, intervalSec: Int, ips: List<String>, appId: String) {
        ips.forEach { ip ->
            if (ip.isNotBlank()) scheduleRelaunch(context, intervalSec, ip, appId)
        }
    }

    fun cancelRelaunchAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag("relaunch")
    }
}
