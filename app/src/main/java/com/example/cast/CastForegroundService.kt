package com.example.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.R
import java.net.Inet4Address
import java.net.NetworkInterface

class CastForegroundService : Service() {

    companion object {
        private const val TAG = "CastForegroundService"
        private const val CHANNEL_ID = "cast_stream_channel"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_START = "com.example.cast.action.START"
        const val ACTION_STOP = "com.example.cast.action.STOP"
        const val EXTRA_TITLE = "extra_title"

        fun start(context: Context, title: String = "Casting to TV") {
            try {
                val intent = Intent(context, CastForegroundService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_TITLE, title)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start CastForegroundService", e)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, CastForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop CastForegroundService", e)
            }
        }

        fun getWifiIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces().toList()
                val ordered = interfaces.sortedBy { iface ->
                    when {
                        iface.name.startsWith("wlan", ignoreCase = true) || iface.name.startsWith("wifi", ignoreCase = true) -> 0
                        iface.name.startsWith("eth", ignoreCase = true) -> 1
                        else -> 2
                    }
                }
                for (iface in ordered) {
                    if (iface.isLoopback || !iface.isUp) continue
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val host = addr.hostAddress ?: ""
                            if (host.isNotBlank() && !host.startsWith("127.") && !host.startsWith("169.254.")) {
                                return host
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting Wi-Fi IP", e)
            }
            return "127.0.0.1"
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AnyFilm:CastWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 60 * 1000L) // Max 10 hours
            }

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager?.createWifiLock(lockMode, "AnyFilm:CastWifiLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "WakeLock & WifiLock acquired successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire locks: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundService()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Casting to TV"
        val notification = buildNotification(title)
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    private fun buildNotification(title: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AnyFilm")
            .setContentText("Casting $title to TV")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Media Casting",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps connection alive while streaming media to your TV"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun stopForegroundService() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing locks: ${e.message}")
        }
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        stopForegroundService()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
