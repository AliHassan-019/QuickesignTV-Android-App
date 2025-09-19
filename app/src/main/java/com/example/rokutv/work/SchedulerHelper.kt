package com.example.rokutv.work

import android.content.Context
import androidx.work.*
import java.util.Calendar
import java.util.concurrent.TimeUnit

object SchedulerHelper {

    // ---------- Auto Relaunch (per IP, any interval >= 1 sec) ----------
    fun scheduleRelaunchForAll(context: Context, intervalSec: Int, ips: List<String>, appId: String) {
        val wm = WorkManager.getInstance(context)
        ips.forEach { ip ->
            scheduleRelaunch(context, intervalSec, ip, appId)
        }
    }

    fun scheduleRelaunch(context: Context, intervalSec: Int, ip: String, appId: String) {
        val delay = intervalSec.coerceAtLeast(1).toLong()
        val data = workDataOf(
            "ip" to ip,
            "command" to "launch/$appId",
            "relaunch" to true,
            "relaunchIntervalSec" to delay.toInt(),
            "appId" to appId
        )
        val req = OneTimeWorkRequestBuilder<RokuWorker>()
            .setInitialDelay(delay, TimeUnit.SECONDS)
            .setInputData(data)
            .addTag("relaunch")
            .addTag("relaunch_$ip")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "relaunch_$ip",
            ExistingWorkPolicy.REPLACE,
            req
        )
    }

    fun cancelRelaunchAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag("relaunch")
    }

    // ---------- Daily Scheduling (per IP, repeats itself daily) ----------
    fun scheduleDailyForAll(context: Context, type: String, hour: Int, minute: Int, ips: List<String>) {
        ips.forEach { ip -> scheduleDaily(context, type, hour, minute, ip) }
    }

    fun scheduleDaily(context: Context, type: String, hour: Int, minute: Int, ip: String) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) add(Calendar.DAY_OF_YEAR, 1)
        }
        val delay = cal.timeInMillis - System.currentTimeMillis()

        val command = if (type.equals("ON", true)) "keypress/PowerOn" else "keypress/PowerOff"
        val tag = if (type.equals("ON", true)) "daily_on" else "daily_off"

        val data = workDataOf(
            "command" to command,
            "ip" to ip,
            "repeatDaily" to true,
            "type" to type.uppercase(),
            "hour" to hour,
            "minute" to minute
        )

        val request = OneTimeWorkRequestBuilder<RokuWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .addTag(tag)
            .addTag("${tag}_$ip")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "${tag}_$ip",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancelDailyAll(context: Context, type: String) {
        val tag = if (type.equals("ON", true)) "daily_on" else "daily_off"
        WorkManager.getInstance(context).cancelAllWorkByTag(tag)
    }
}
