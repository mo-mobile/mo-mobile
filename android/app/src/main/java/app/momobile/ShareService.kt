package app.momobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject

/**
 * Sharing: while it's on, this keeps running in the background (with a
 * notification) and prints whatever comes into the queue, from friends and
 * from you, one at a time, in order. Works with the phone locked.
 */
class ShareService : Service() {

    companion object {
        private const val TAG = "MoMobile"
        const val CHANNEL = "sharing"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_START = "app.momobile.SHARE_START"
        private const val ACTION_STOP = "app.momobile.SHARE_STOP"

        /** True while the service is running. */
        @Volatile var running = false
            private set

        /** Prints waiting or printing, as last heard from the server. */
        @Volatile var waiting = 0
            private set

        /** Turn Sharing on: tells the server, then starts printing the queue. */
        fun start(context: Context) {
            context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(ShareClient.KEY_ON, true).apply()
            val intent = Intent(context, ShareService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        /** Turn Sharing off: tells the server, then stops. */
        fun stop(context: Context) {
            context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(ShareClient.KEY_ON, false).apply()
            if (running) {
                context.startService(Intent(context, ShareService::class.java).setAction(ACTION_STOP))
            } else {
                Thread { try { ShareClient(context).update(JSONObject().put("sharing", false)) } catch (e: Exception) {} }.start()
            }
        }

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Sharing", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Shows while friends can print to your printer"
                    })
            }
        }
    }

    @Volatile private var stopping = false
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel(this)
        val notification = build("Starting…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (intent?.action == ACTION_STOP) {
            stopping = true
            Thread {
                try { ShareClient(this).update(JSONObject().put("sharing", false)) } catch (e: Exception) {
                    Log.w(TAG, "couldn't tell the server Sharing is off", e)
                }
            }.start()
            worker?.interrupt()
            finish()
            return START_NOT_STICKY
        }

        if (!running) {
            running = true
            stopping = false
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MoMobile:sharing")
                .apply { setReferenceCounted(false); acquire() }
            worker = Thread { loop() }.apply { start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        worker?.interrupt()
        running = false
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun finish() {
        running = false
        waiting = 0
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun loop() {
        val client = ShareClient(this)
        var told = false
        while (!stopping) {
            try {
                if (!told) {
                    client.update(JSONObject().put("sharing", true))
                    told = true
                    notify(null)
                }
                val job = client.next(25)
                if (job == null) { notify(null); continue }
                val id = job.getLong("id")
                waiting = job.optInt("waiting", 1)
                notify("Printing ${job.optString("label")} for ${job.optString("name")}")
                try {
                    val payload = client.bytes(id)
                    PrinterLink.print(this, payload).get()
                    client.done(id, true)
                    waiting = maxOf(0, waiting - 1)
                    notify(null)
                } catch (e: Exception) {
                    val cause = e.cause ?: e
                    Log.w(TAG, "shared print failed", cause)
                    client.done(id, false, cause.message ?: "Print failed")
                    notify("Printer not responding. Turn it on; printing resumes by itself.")
                    Thread.sleep(10_000)
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                // No connection: keep trying quietly.
                Log.w(TAG, "sharing poll failed", e)
                notify("Waiting for internet…")
                try { Thread.sleep(5_000) } catch (ie: InterruptedException) { break }
            }
        }
    }

    private fun notify(status: String?) {
        if (stopping) return
        val text = status ?: when (waiting) {
            0 -> "Friends can print from your printer's QR code."
            1 -> "1 print in the queue."
            else -> "$waiting prints in the queue."
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(text))
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_QUEUE)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val off = PendingIntent.getBroadcast(
            this, 1, Intent(this, ShareActions::class.java).setAction(ShareActions.ACTION_OFF),
            PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Sharing is on")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Turn off", off).build())
            .build()
    }
}
