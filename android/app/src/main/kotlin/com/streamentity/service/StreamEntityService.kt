package com.streamentity.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.streamentity.MainActivity
import com.streamentity.engine.StreamServer
import com.streamentity.engine.TorrentEngine

class StreamEntityService : Service() {
    private val TAG = "StreamEntityService"
    private val binder = LocalBinder()

    var torrentEngine: TorrentEngine? = null
        private set
    var streamServer: StreamServer? = null
        private set

    inner class LocalBinder : Binder() {
        fun getService(): StreamEntityService = this@StreamEntityService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Starting StreamEntity Service...")
        startForegroundNotification()
        startEngineAndServer()
    }

    private fun startForegroundNotification() {
        try {
            val channelId = "stream_entity_playback_channel"
            val channelName = "StreamEntity Media Server"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    channelName,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Background sequential streaming engine & local media server"
                    setShowBadge(false)
                }
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
            }

            val launchIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification: Notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("StreamEntity Engine")
                .setContentText("Sequential streaming server running locally (127.0.0.1:3000)")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    1001,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(1001, notification)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Foreground notification skipped (OEM/Permission policy): ${e.message}")
        }
    }

    private fun startEngineAndServer() {
        try {
            if (torrentEngine == null) {
                torrentEngine = TorrentEngine(applicationContext).apply {
                    start()
                }
            }
            if (streamServer == null && torrentEngine != null) {
                streamServer = StreamServer(applicationContext, torrentEngine!!, 3000).apply {
                    start()
                }
                Log.i(TAG, "StreamEntity HTTP & WebSocket server listening on port 3000")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing TorrentEngine or StreamServer", e)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Stopping StreamEntity Service...")
        try {
            streamServer?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping StreamServer", e)
        }
        try {
            torrentEngine?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TorrentEngine", e)
        }
        super.onDestroy()
    }
}
