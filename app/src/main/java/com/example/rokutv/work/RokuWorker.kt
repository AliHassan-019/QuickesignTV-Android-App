package com.example.rokutv.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.rokutv.network.RokuApiService

class RokuWorker(appContext: Context, workerParams: WorkerParameters) :
    Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val ip = inputData.getString("ip") ?: return Result.failure()
        val command = inputData.getString("command") ?: return Result.failure()

        val label = labelForCommand(command)
        Log.d("RokuWorker", "$label → $ip")

        val ok = RokuApiService.sendCommand(ip, command)
        notify(label, if (ok) "Success → $ip" else "Failed → $ip")

        if (!ok) {
            Log.w("RokuWorker", "$label failed → $ip, will retry")
            return Result.retry()
        }

        // Re-schedule daily (per IP)
        val repeatDaily = inputData.getBoolean("repeatDaily", false)
        if (repeatDaily) {
            val type = inputData.getString("type") ?: return Result.success()
            val hour = inputData.getInt("hour", 0)
            val minute = inputData.getInt("minute", 0)
            SchedulerHelper.scheduleDaily(applicationContext, type, hour, minute, ip)
        }

        // Re-chain relaunch (per IP)
        if (inputData.getBoolean("relaunch", false)) {
            val interval = inputData.getInt("relaunchIntervalSec", 30)
            val appId = inputData.getString("appId") ?: return Result.success()
            SchedulerHelper.scheduleRelaunch(applicationContext, interval, ip, appId)
        }

        return Result.success()
    }

    private fun labelForCommand(command: String): String = when {
        command.startsWith("keypress/PowerOn", true) -> "Power On"
        command.startsWith("keypress/PowerOff", true) -> "Power Off"
        command.startsWith("launch/", true) -> "Launch"
        else -> command
    }

    private fun notify(title: String, text: String) {
        val channelId = "roku_ops"
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "Roku Operations", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val notif = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext)
            .notify((System.currentTimeMillis() % 100000).toInt(), notif)
    }
}
