package app.momobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject

/**
 * Push messages from the Sharing server:
 *  - share_request: someone scanned your printer's code while Sharing was
 *    off. Shows "Gary wants to print. Turn on Sharing?" with a Turn on button.
 *  - job: a print came in and your phone hasn't checked in for a while
 *    (asleep). Wakes the Sharing service so it picks the print up.
 */
class PushService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "MoMobile"
        const val CHANNEL = "share_requests"
        const val REQUEST_ID = 3

        /** Keep the server's copy of this phone's push address up to date. */
        fun sendToken(context: Context, token: String) {
            val prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(ShareClient.KEY_FCM, null) == token && prefs.getBoolean("share_fcm_sent", false)) return
            prefs.edit().putString(ShareClient.KEY_FCM, token).putBoolean("share_fcm_sent", false).apply()
            Thread {
                try {
                    val client = ShareClient(context)
                    if (client.registered) {
                        client.update(JSONObject().put("fcm", token))
                        prefs.edit().putBoolean("share_fcm_sent", true).apply()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "couldn't send push token", e)
                }
            }.start()
        }
    }

    override fun onNewToken(token: String) {
        sendToken(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        when (message.data["type"]) {
            "share_request" -> {
                if (ShareService.running || !prefs.getBoolean(ShareClient.KEY_PUSH, true)) return
                showRequest(message.data["name"] ?: "Someone")
            }
            "job" -> {
                if (prefs.getBoolean(ShareClient.KEY_ON, false) && !ShareService.running) {
                    try { ShareService.start(this) } catch (e: Exception) { Log.w(TAG, "couldn't wake Sharing", e) }
                }
            }
        }
    }

    private fun showRequest(name: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Print requests", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When someone scans your printer's code while Sharing is off"
                })
        }
        val on = PendingIntent.getBroadcast(
            this, 2, Intent(this, ShareActions::class.java).setAction(ShareActions.ACTION_ON),
            PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(
            this, 4, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        nm.notify(REQUEST_ID, builder
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("$name wants to print")
            .setContentText("Turn on Sharing?")
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Turn on", on).build())
            .build())
    }
}
