package com.company.vehiclevoice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

class VoiceForegroundService : Service() {

    private var currentState = STATE_STOPPED

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                updateState(STATE_STOPPING, "Foreground voice service is stopping.")
                stopSelf()
                START_NOT_STICKY
            }

            ACTION_START, null -> {
                startM1ForegroundService()
                START_STICKY
            }

            else -> {
                updateState(currentState, "Unknown service action ignored: ${intent.action}")
                START_STICKY
            }
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        getSystemService(NotificationManager::class.java).cancel(VOICE_NOTIFICATION_ID)
        sendStatusBroadcast(STATE_STOPPED, "Foreground voice service stopped.")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startM1ForegroundService() {
        currentState = STATE_IDLE_KWS
        startForeground(
            VOICE_NOTIFICATION_ID,
            buildNotification("M1 service running. Waiting for future KWS wiring."),
        )
        updateState(
            STATE_IDLE_KWS,
            "Foreground voice service started. M1 only keeps the service alive; audio/KWS are not active yet.",
        )
    }

    private fun updateState(state: String, message: String) {
        currentState = state

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(
            VOICE_NOTIFICATION_ID,
            buildNotification("Current state: $state"),
        )

        sendStatusBroadcast(state, message)
    }

    private fun sendStatusBroadcast(state: String, message: String) {
        val statusIntent = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_MESSAGE, message)
        }
        sendBroadcast(statusIntent)
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopServiceIntent = PendingIntent.getService(
            this,
            1,
            createStopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_voice_service)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                R.drawable.ic_voice_service,
                getString(R.string.stop_service),
                stopServiceIntent,
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.company.vehiclevoice.action.START"
        const val ACTION_STOP = "com.company.vehiclevoice.action.STOP"
        const val ACTION_STATUS = "com.company.vehiclevoice.action.STATUS"

        const val EXTRA_STATE = "extra_state"
        const val EXTRA_MESSAGE = "extra_message"

        const val STATE_UNKNOWN = "UNKNOWN"
        const val STATE_STOPPED = "STOPPED"
        const val STATE_STOPPING = "STOPPING"
        const val STATE_IDLE_KWS = "IDLE_KWS"

        private const val CHANNEL_ID = "voice_foreground_service"
        private const val VOICE_NOTIFICATION_ID = 20260607

        fun createStartIntent(context: Context): Intent {
            return Intent(context, VoiceForegroundService::class.java).apply {
                action = ACTION_START
            }
        }

        fun createStopIntent(context: Context): Intent {
            return Intent(context, VoiceForegroundService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }
}
