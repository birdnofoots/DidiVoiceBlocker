package com.didi.voiceblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Calendar

class DriverTimerService : Service() {
    companion object {
        const val TAG = "DriverTimer"
        const val NOTIFICATION_ID = 1003
        const val CHANNEL_ID = "driver_timer_channel"
        private const val CHECK_INTERVAL_MS = 60000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            DriverDataStore.checkDayReset()
            tick()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        DriverDataStore.init(this)
        // 如果之前被手动停止，不再自动启动计时
        if (DriverDataStore.timerStopped) {
            stopSelf()
            return
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        handler.post(timerRunnable)
        Log.d(TAG, "DriverTimerService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(timerRunnable)
        super.onDestroy()
    }

    private fun tick() {
        if (DriverDataStore.manualPaused) return
        if (DriverDataStore.timerStopped) return

        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY
        val isWeekday = !isWeekend

        val type = getCurrentPeakType(hour, minute, isWeekday, isWeekend)
        if (type != null) {
            DriverDataStore.addPeakMinutes(type, CHECK_INTERVAL_MS)
            broadcastUpdate()
        }
    }

    private fun getCurrentPeakType(hour: Int, minute: Int, isWeekday: Boolean, isWeekend: Boolean): String? {
        val timeMinutes = hour * 60 + minute

        if (isWeekday) {
            when {
                timeMinutes in 420..599 -> return "morning"   // 07:00-09:59
                timeMinutes in 1020..1139 -> return "evening" // 17:00-18:59
                timeMinutes in 1200..1319 -> return "night"   // 20:00-21:59
            }
        }

        if (isWeekend) {
            when {
                timeMinutes in 660..839 -> return "weekend"   // 11:00-13:59
                timeMinutes in 1080..1199 -> return "weekend" // 18:00-19:59
                timeMinutes in 1200..1319 -> return "weekend" // 20:00-21:59
            }
        }

        return null
    }

    private fun broadcastUpdate() {
        sendBroadcast(Intent(DashboardOverlayService.ACTION_REFRESH_DISPLAY).setPackage(packageName))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "高峰计时", NotificationManager.IMPORTANCE_LOW)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("高峰计时运行中")
            .setContentText("正在累计高峰时段")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }
}
