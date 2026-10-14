package app.momobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps the app alive while cards download in "Everything on this phone"
 * mode, so people can use other apps meanwhile. Only started when the user
 * ticks "Keep downloading when I leave the app" on the setup page.
 *
 * The download itself runs in the app's built-in server (Python); this just
 * holds the process in the foreground, shows progress, and stops when done.
 */
class DownloadService : Service() {

    companion object {
        private const val TAG = "MoMobile"
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 1
        private const val STATUS_URL = "http://127.0.0.1:8421/api/setup/status"

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }

    @Volatile private var running = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notification = build("Starting…", 0, 0)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (!running) {
            running = true
            // Keep the CPU awake if the screen turns off; released when done.
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MoMobile:download")
                .apply { acquire(3 * 60 * 60 * 1000L) }
            Thread { watch() }.start()
        }
        return START_NOT_STICKY
    }

    /** Poll the built-in server until the download finishes. */
    private fun watch() {
        var sawRunning = false
        var idle = 0
        while (running) {
            try {
                val status = fetchStatus()
                if (status.optBoolean("running")) {
                    sawRunning = true
                    idle = 0
                    val detail = status.optJSONObject("detail")
                    val title = status.optString("title", "Downloading cards")
                    val step = status.optInt("step", 0)
                    val total = status.optInt("total", 6)
                    val text = if (detail != null) {
                        "$title: ${detail.optInt("done")} of ${detail.optInt("total")}"
                    } else "Step $step of $total: $title"
                    notify(build(text, step, total))
                } else if (sawRunning || ++idle > 10) {
                    finish(status.optString("state"))
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "download status: ${e.message}")
            }
            Thread.sleep(3000)
        }
    }

    private fun fetchStatus(): JSONObject {
        val conn = URL(STATUS_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        return conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
    }

    private fun finish(state: String) {
        val ok = state == "done"
        running = false
        wakeLock?.let { if (it.isHeld) it.release() }
        val prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(MainActivity.KEY_REVOKE_NOTIFICATIONS, false) &&
            Build.VERSION.SDK_INT >= 33) {
            // Android hands the permission back the next time the app closes.
            revokeSelfPermissionOnKill(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        if (canNotify()) {
            notify(Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(when (state) {
                    "done" -> "Cards downloaded"
                    "paused" -> "Card download paused"
                    else -> "Card download stopped"
                })
                .setContentText(when (state) {
                    "done" -> "Mo Mobile is ready to print."
                    "paused" -> "Open Mo Mobile to resume."
                    else -> "Open Mo Mobile to try again."
                })
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build(), id = NOTIFICATION_ID + 1)
        }
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun canNotify() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun build(text: String, step: Int, total: Int): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading cards")
            .setContentText(text)
            .setProgress(total, step, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()

    private fun notify(n: Notification, id: Int = NOTIFICATION_ID) {
        if (canNotify()) getSystemService(NotificationManager::class.java).notify(id, n)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL, "Card downloads", NotificationManager.IMPORTANCE_LOW))
        }
    }
}
