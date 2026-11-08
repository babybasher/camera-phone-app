package net.homecam.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Android doesn't let an app turn the camera on by itself after a restart, so
 * if the camera was on, show a notification: one tap turns it back on.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val cfg = Prefs(ctx)
        if (!cfg.ready || !cfg.running) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("restart", "Camera restart", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java).putExtra("start", true), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, "restart")
            .setSmallIcon(R.drawable.ic_cam)
            .setContentTitle("${cfg.name.ifEmpty { "Phone" }} camera is off")
            .setContentText("The phone restarted. Tap to turn the camera back on.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(2, n)
    }
}
