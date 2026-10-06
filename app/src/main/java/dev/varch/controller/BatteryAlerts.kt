package dev.varch.controller

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.varch.controller.net.BatteryInfo
import dev.varch.controller.net.VarchClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Checks the desktop's battery every 15 minutes (the shortest period Android
 * allows) and notifies once when it runs low or finishes charging.
 */
object BatteryAlerts {
    private const val WORK = "battery-alerts"
    private const val CHANNEL = "battery"
    private const val NOTIFICATION_ID = 1
    const val LOW_PERCENT = 20

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<Worker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    /** The event worth a notification for this reading, or "" for none. */
    fun eventFor(battery: BatteryInfo): String = when {
        battery.full || (battery.charging && battery.percent >= 100) -> "full"
        !battery.charging && battery.percent <= LOW_PERCENT -> "low"
        else -> ""
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val store = Store(applicationContext)
            val pairing = store.load() ?: return Result.success()
            val battery = try {
                VarchClient().status(pairing.endpoint, pairing.token).battery
            } catch (_: IOException) {
                // The desktop being asleep or off the network is normal, not a failure.
                return Result.success()
            } ?: return Result.success()

            val event = eventFor(battery)
            if (event != store.lastAlert) {
                store.lastAlert = event
                if (event.isNotEmpty()) notify(pairing.hostName, event, battery.percent)
            }
            return Result.success()
        }

        private fun notify(host: String, event: String, percent: Int) {
            val context = applicationContext
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.channel_battery), NotificationManager.IMPORTANCE_DEFAULT),
            )
            val text = if (event == "low") "Battery is at $percent%. Plug it in." else "Battery is fully charged."
            manager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_battery)
                    .setContentTitle(host)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }
}
