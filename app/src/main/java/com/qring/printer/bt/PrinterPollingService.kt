package com.qring.printer.bt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.qring.printer.MainActivity
import com.qring.printer.R
import com.qring.printer.model.PrinterStatusRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 打印机轮询前台服务。
 * 当 App 在前台时启动，保持连接并轮询状态。
 * App 退到后台时自动停止（不保活，尊重系统省电策略）。
 *
 * 如需后台保活，改用 WorkManager 定期重连。
 */
class PrinterPollingService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var pollJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            startForeground(NOTIFICATION_ID, buildNotification())
            startPolling()
            START_NOT_STICKY
        } catch (e: Exception) {
            Timber.w(e, "startForeground failed, skip service")
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        stopPolling()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            val printer = PrinterConnection.getInstance()
            while (isActive) {
                try {
                    if (printer.isAlive() && !printer.isBusy()) {
                        printer.refreshAll()
                    }
                } catch (e: Exception) {
                    Timber.tag("PollService").w(e, "poll failed")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "打印机连接",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持与打印机的连接"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val status = PrinterStatusRepository._value
        val contentText = if (status.connState == com.qring.printer.model.ConnState.CONNECTED) {
            "已连接 ${status.deviceName}"
        } else {
            "打印机未连接"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("错题小印")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "qringprint_printer"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_INTERVAL_MS = 10000L

        fun start(context: Context) {
            val intent = Intent(context, PrinterPollingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PrinterPollingService::class.java))
        }
    }
}
