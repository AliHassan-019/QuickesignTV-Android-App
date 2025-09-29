package com.example.rokutv.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.rokutv.data.RokuPreferences
import com.example.rokutv.network.RokuApiService

class RokuWorker(appContext: Context, workerParams: WorkerParameters) :
    Worker(appContext, workerParams) {

    companion object {
        const val ACTION_FEEDBACK = "com.example.rokutv.FEEDBACK"
        const val EXTRA_MESSAGE = "message"
    }

    override fun doWork(): Result {
        val ip = inputData.getString("ip") ?: return Result.failure()
        val command = inputData.getString("command") ?: return Result.failure()
        val isRelaunch = inputData.getBoolean("relaunch", false)

        val prefs = RokuPreferences(applicationContext)
        val label = labelFor(command)

        // ---- Relaunch suppression check ----
        if (isRelaunch && command.startsWith("launch/", true)) {
            if (prefs.isSuppressed(ip)) {
                // ✅ FIX: call through RokuApiService
                val on = RokuApiService.isPoweredOn(ip)
                if (!on) {
                    val msg = "Skipped relaunch on $ip (TV is OFF)"
                    Log.i("RokuWorker", msg)
                    toastToApp(msg)
                    notify("Auto Relaunch", msg)
                    rescheduleRelaunch()
                    return Result.success()
                } else {
                    prefs.clearSuppression(ip) // TV is back on → resume relaunch
                }
            }
        }

        // ---- Execute command ----
        val ok = RokuApiService.sendCommand(ip, command)

        if (isRelaunch && command.startsWith("launch/", true)) {
            val msg = if (ok) "Auto relaunch on $ip" else "Auto relaunch failed on $ip"
            (if (ok) Log.i("RokuWorker", msg) else Log.w("RokuWorker", msg))
            toastToApp(msg)
            notify("Auto Relaunch", msg)
        } else {
            val msg = if (ok) "$label → $ip" else "$label failed → $ip"
            (if (ok) Log.i("RokuWorker", msg) else Log.w("RokuWorker", msg))
        }

        if (!ok) return Result.retry()

        // ---- Maintain suppression ----
        if (command.startsWith("keypress/PowerOff", true)) prefs.suppressIp(ip)
        if (command.startsWith("keypress/PowerOn", true)) prefs.clearSuppression(ip)

        // ---- Daily self-reschedule ----
        if (inputData.getBoolean("repeatDaily", false)) {
            val type = inputData.getString("type") ?: return Result.success()
            val hour = inputData.getInt("hour", 0)
            val minute = inputData.getInt("minute", 0)
            SchedulerHelper.scheduleDaily(applicationContext, type, hour, minute, ip)
        }

        // ---- Relaunch reschedule ----
        if (isRelaunch) rescheduleRelaunch()

        return Result.success()
    }

    private fun rescheduleRelaunch() {
        val ip = inputData.getString("ip") ?: return
        val appId = inputData.getString("appId") ?: return
        val interval = inputData.getInt("relaunchIntervalSec", 30)
        SchedulerHelper.scheduleRelaunch(applicationContext, interval, ip, appId)
    }

    private fun labelFor(command: String): String = when {
        command.startsWith("keypress/PowerOn", true) -> "Power On"
        command.startsWith("keypress/PowerOff", true) -> "Power Off"
        command.startsWith("launch/", true) -> "Launch"
        else -> command
    }

    // Feedback into the app
    private fun toastToApp(message: String) {
        val intent = Intent(ACTION_FEEDBACK)
            .setPackage(applicationContext.packageName)
            .putExtra(EXTRA_MESSAGE, message)
        applicationContext.sendBroadcast(intent)
    }

    private fun notify(title: String, text: String) {
        val channelId = "roku_ops"
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "Roku Operations", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
        }
        val notif = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext)
            .notify((System.currentTimeMillis() % 100000).toInt(), notif)
    }
}
