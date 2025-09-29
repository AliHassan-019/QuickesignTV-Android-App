package com.example.rokutv.work

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.Calendar
import java.util.concurrent.TimeUnit

object SchedulerHelper {

    // ---------- Auto Relaunch ----------
    fun scheduleRelaunchForAll(context: Context, intervalSec: Int, ips: List<String>, appId: String) {
        ips.forEach { ip -> scheduleRelaunch(context, intervalSec, ip, appId) }
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

        Log.d("SchedulerHelper", "Scheduled relaunch for $ip every $delay sec")
    }

    fun cancelRelaunchAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag("relaunch")
        Log.d("SchedulerHelper", "Canceled all relaunch jobs")
    }

    // ---------- Daily Scheduling ----------
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

        Log.d("SchedulerHelper", "Scheduled daily $type for $ip at $hour:$minute (delay=$delay ms)")
    }

    fun cancelDailyAll(context: Context, type: String) {
        val tag = if (type.equals("ON", true)) "daily_on" else "daily_off"
        WorkManager.getInstance(context).cancelAllWorkByTag(tag)
        Log.d("SchedulerHelper", "Canceled all $type jobs")
    }
}
