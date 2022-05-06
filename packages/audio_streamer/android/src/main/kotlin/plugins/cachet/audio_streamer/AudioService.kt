package plugins.cachet.audio_streamer

import android.app.Notification
import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.support.v4.media.MediaBrowserCompat
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val acquired = AtomicBoolean(false)

class AudioService : androidx.media.MediaBrowserServiceCompat() {
    private val tag = "AudioService"
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var notification: Notification? = null
    private var notificationManager: NotificationManagerCompat? = null
    override fun onCreate() {
        super.onCreate()
        notificationManager = NotificationManagerCompat.from(this)

        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "noise_detector:wifiLock")

        wakeLock = (applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "noise_detector:wakeLock"
            )

        wifiLock?.setReferenceCounted(false)
        wakeLock?.setReferenceCounted(false)
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if (acquired.compareAndSet(false, true)) {
            Log.d(tag, "acquired")
            wifiLock?.acquire()
            wakeLock?.acquire(TimeUnit.MINUTES.toMillis(500))
        }
        Log.d(tag, "onStartCommand")
//        onTaskRemoved(intent)
        Toast.makeText(
            applicationContext, "This is a Service running in Background",
            Toast.LENGTH_SHORT
        ).show()

        setupNotificationChannel()

        notification = NotificationCompat.Builder(applicationContext, CHANNEL_NOTIFICATION)
            .setSmallIcon(R.drawable.ic_media_play)
            .setContentTitle("Noise Detector")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentText("Detecting noises....")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        return START_STICKY
    }

    override fun onDestroy() {
        Toast.makeText(this, "Service Destroyed", Toast.LENGTH_SHORT).show()
        if (acquired.compareAndSet(true, false)) {
            Log.d(tag, "onDestroy - wakeLock released")
            wakeLock?.release()
            wifiLock?.release()
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent) {
        Log.d("AudioService", "onTaskRemoved")
        if (acquired.compareAndSet(true, false)) {
            Log.d(tag, "onTaskRemoved - wakeLock released")
            wakeLock?.release()
        }
        Thread {
            notificationManager?.cancel(NOTIFICATION_ID)
        }.start()
        stopForeground(true)
        super.onTaskRemoved(rootIntent)
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot("/", null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.sendResult(mutableListOf())
    }

    private fun setupNotificationChannel() {
        Log.d(tag, "setupNotificationChannel")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationChannel = NotificationChannel(
                CHANNEL_NOTIFICATION,
                "Noise Detector",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Informa que esta detectando barulhos"
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    this.vibrationPattern = longArrayOf(0)
                    this.enableVibration(true)
                }
            }
            notificationManager?.createNotificationChannel(notificationChannel)
        }
    }
}