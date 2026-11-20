package glick.android

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

/**
 * Keeps the click going while the screen is locked or another app is in
 * front. Android only lets an app keep running there as a foreground service
 * with a visible notification; this one exists for exactly as long as the
 * click plays, and holds the processor awake for that time.
 */
class PlaybackService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires every start to be followed by startForeground, also one that only ends the service.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        when (intent?.action) {
            ACTION_STOP_CLICK -> (application as GlickApplication).metronome.stop()
            ACTION_END -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "glick:playback")
                    .apply { acquire(MAX_AWAKE_MILLIS) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            // Low importance: it sits in the shade without a sound of its own.
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PlaybackService::class.java).setAction(ACTION_STOP_CLICK), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("glick is playing")
            .setContentText("Tap to open")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP_CLICK = "glick.android.STOP_CLICK"
        private const val ACTION_END = "glick.android.END"

        /** A safety net: a practice session is never longer than this. */
        private const val MAX_AWAKE_MILLIS = 12 * 60 * 60 * 1000L

        /** Brings the service up when the click starts and takes it down when it stops. */
        fun follow(context: Context, playing: Boolean) {
            val intent = Intent(context, PlaybackService::class.java)
            // Starting can be refused when the app is not in front; the click then plays without the service.
            runCatching {
                if (playing) context.startForegroundService(intent)
                else context.startService(intent.setAction(ACTION_END))
            }
        }
    }
}
