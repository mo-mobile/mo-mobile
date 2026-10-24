package app.momobile

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The Turn on / Turn off buttons on the Sharing notifications. */
class ShareActions : BroadcastReceiver() {

    companion object {
        const val ACTION_ON = "app.momobile.SHARE_ON"
        const val ACTION_OFF = "app.momobile.SHARE_OFF"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ON -> {
                context.getSystemService(NotificationManager::class.java).cancel(PushService.REQUEST_ID)
                ShareService.start(context)
            }
            ACTION_OFF -> ShareService.stop(context)
        }
    }
}
