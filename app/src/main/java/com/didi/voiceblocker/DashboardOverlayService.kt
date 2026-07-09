package com.didi.voiceblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class DashboardOverlayService : Service() {
    companion object {
        const val TAG = "DashboardOverlay"
        const val NOTIFICATION_ID = 1004
        const val CHANNEL_ID = "dashboard_channel"
        const val ACTION_REFRESH_DISPLAY = "com.didi.voiceblocker.REFRESH_DISPLAY"
        private const val UI_REFRESH_MS = 2000L
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isMinimized = false
    private val handler = Handler(Looper.getMainLooper())

    private var tvOrders: TextView? = null
    private var tvMorning: TextView? = null
    private var tvEvening: TextView? = null
    private var tvNight: TextView? = null
    private var tvWeekend: TextView? = null
    private var tvTotal: TextView? = null
    private var btnPause: Button? = null

    private val displayReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshDisplay()
        }
    }

    private val uiRefreshRunnable = object : Runnable {
        override fun run() {
            refreshDisplay()
            handler.postDelayed(this, UI_REFRESH_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        DriverDataStore.init(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        LocalBroadcastManager.getInstance(this).registerReceiver(
            displayReceiver, IntentFilter(ACTION_REFRESH_DISPLAY)
        )

        showOverlay()
        handler.post(uiRefreshRunnable)
        Log.d(TAG, "DashboardOverlayService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (overlayView == null) showOverlay()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(uiRefreshRunnable)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(displayReceiver)
        removeOverlay()
        super.onDestroy()
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.dashboard_overlay, null)
        setupViews()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        layoutParams = params
        windowManager?.addView(overlayView, params)
    }

    private fun setupViews() {
        val view = overlayView ?: return
        tvOrders = view.findViewById(R.id.tvOrders)
        tvMorning = view.findViewById(R.id.tvMorning)
        tvEvening = view.findViewById(R.id.tvEvening)
        tvNight = view.findViewById(R.id.tvNight)
        tvWeekend = view.findViewById(R.id.tvWeekend)
        tvTotal = view.findViewById(R.id.tvTotal)
        btnPause = view.findViewById(R.id.btnPause)

        // Make draggable
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.findViewById<View>(R.id.dragHandle)?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val params = overlayView?.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val params = overlayView?.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(overlayView, params)
                    true
                }
                else -> false
            }
        }

        // Close button
        view.findViewById<View>(R.id.btnClose)?.setOnClickListener {
            stopSelf()
        }

        // Minimize button — shrink to small icon
        view.findViewById<View>(R.id.btnMinimize)?.setOnClickListener {
            toggleMinimize()
        }

        // Click on header area (when minimized) also restores
        view.findViewById<View>(R.id.dragHandle)?.setOnClickListener {
            if (isMinimized) toggleMinimize()
        }

        // Refresh orders button
        view.findViewById<View>(R.id.btnRefreshOrders)?.setOnClickListener {
            LocalBroadcastManager.getInstance(this).sendBroadcast(
                Intent(DashboardAccessibilityService.ACTION_REFRESH_ORDERS)
            )
        }

        // Pause/resume button
        btnPause?.setOnClickListener {
            val newPaused = !DriverDataStore.manualPaused
            DriverDataStore.setManualPaused(newPaused)
            updatePauseButton()
        }

        // Start DriverTimerService if not running
        startService(Intent(this, DriverTimerService::class.java))
    }

    private fun toggleMinimize() {
        val params = layoutParams ?: return
        val density = resources.displayMetrics.density
        val iconHeight = (40 * density).toInt()

        if (isMinimized) {
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            overlayView?.apply {
                findViewById<LinearLayout>(R.id.contentArea)?.visibility = View.VISIBLE
                findViewById<View>(R.id.btnMinimize)?.setBackgroundColor(0xFF555555.toInt())
                requestLayout()
            }
            windowManager?.updateViewLayout(overlayView, params)
            isMinimized = false
        } else {
            params.height = iconHeight
            overlayView?.apply {
                findViewById<LinearLayout>(R.id.contentArea)?.visibility = View.GONE
                requestLayout()
            }
            windowManager?.updateViewLayout(overlayView, params)
            isMinimized = true
        }
    }

    private fun refreshDisplay() {
        if (overlayView == null) return
        handler.post {
            tvOrders?.text = "订单数:     ${DriverDataStore.getDisplayOrder()}"
            tvMorning?.text = "早高峰总时长: ${DriverDataStore.getDisplayPeak("morning")}"
            tvEvening?.text = "晚高峰总时长: ${DriverDataStore.getDisplayPeak("evening")}"
            tvNight?.text = "夜高峰总时长: ${DriverDataStore.getDisplayPeak("night")}"
            tvWeekend?.text = "周末总时长:   ${DriverDataStore.getDisplayPeak("weekend")}"
            tvTotal?.text = "总高峰时长:  ${DriverDataStore.getTotalPeakHours()}"
            updatePauseButton()
        }
    }

    private fun updatePauseButton() {
        if (DriverDataStore.manualPaused) {
            btnPause?.text = "▶ 恢复计时"
            btnPause?.setBackgroundColor(0xFF4CAF50.toInt())
        } else {
            btnPause?.text = "⏸ 暂停计时"
            btnPause?.setBackgroundColor(0xFFFF9800.toInt())
        }
    }

    private fun removeOverlay() {
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "数据面板", NotificationManager.IMPORTANCE_LOW)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("数据面板运行中")
            .setContentText("高峰计时 + 订单监控")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }
}
