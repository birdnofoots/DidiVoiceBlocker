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
    private var panelParams: WindowManager.LayoutParams? = null
    private var minimizedView: View? = null
    private var minimizedParams: WindowManager.LayoutParams? = null
    private var isMinimized = false
    private var savedX = 0
    private var savedY = 0
    private val handler = Handler(Looper.getMainLooper())

    private var tvOrders: TextView? = null
    private var tvMorning: TextView? = null
    private var tvEvening: TextView? = null
    private var tvNight: TextView? = null
    private var tvWeekend: TextView? = null
    private var tvTotal: TextView? = null

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

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        windowManager?.addView(overlayView, panelParams)
    }

    private fun removeOverlay() {
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
        removeMinimizedIcon()
    }

    private fun removeMinimizedIcon() {
        minimizedView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        minimizedView = null
    }

    private fun setupViews() {
        val view = overlayView ?: return
        tvOrders = view.findViewById(R.id.tvOrders)
        tvMorning = view.findViewById(R.id.tvMorning)
        tvEvening = view.findViewById(R.id.tvEvening)
        tvNight = view.findViewById(R.id.tvNight)
        tvWeekend = view.findViewById(R.id.tvWeekend)
        tvTotal = view.findViewById(R.id.tvTotal)

        // Make the whole panel draggable via dragHandle
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.findViewById<View>(R.id.dragHandle)?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val pp = panelParams ?: return@setOnTouchListener false
                    initialX = pp.x
                    initialY = pp.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val pp = panelParams ?: return@setOnTouchListener false
                    pp.x = initialX + (event.rawX - initialTouchX).toInt()
                    pp.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(overlayView, pp)
                    true
                }
                else -> false
            }
        }

        // Close button — stop service
        view.findViewById<View>(R.id.btnClose)?.setOnClickListener {
            stopSelf()
        }

        // Minimize button — hide panel, show small icon
        view.findViewById<View>(R.id.btnMinimize)?.setOnClickListener {
            minimizePanel()
        }

        // 长按标题栏 → 调试：强制写入 halfMonthOrders=4, startOfDayTotal=4, todayOrders=0
        view.findViewById<View>(R.id.dragHandle)?.setOnLongClickListener {
            DriverDataStore.setManualOrderCount(halfMonthOrders = 4, startOfDayTotal = 4)
            handler.postDelayed({
                val orders = DriverDataStore.getDisplayOrder()
                tvOrders?.text = "订单数:     $orders"
                android.util.Log.d("DASH_DEBUG", "长按后订单显示: $orders")
            }, 500)
            true
        }

        // Refresh orders button
        view.findViewById<View>(R.id.btnRefreshOrders)?.setOnClickListener {
            LocalBroadcastManager.getInstance(this).sendBroadcast(
                Intent(DashboardAccessibilityService.ACTION_REFRESH_ORDERS)
            )
        }

        // Pause/resume button — 停止/开始计时
        view.findViewById<View>(R.id.btnPause)?.setOnClickListener {
            if (DriverDataStore.timerStopped) {
                // 当前停止状态 → 开始计时
                DriverDataStore.setTimerStopped(false)
                DriverDataStore.setManualPaused(false) // 确保计时不被暂停
                startService(Intent(this, DriverTimerService::class.java))
                updatePauseButton()
            } else {
                // 当前运行状态 → 停止计时
                DriverDataStore.setTimerStopped(true)
                stopService(Intent(this, DriverTimerService::class.java))
                updatePauseButton()
            }
        }

        // Start DriverTimerService if not stopped
        if (!DriverDataStore.timerStopped) {
            startService(Intent(this, DriverTimerService::class.java))
        }
    }

    private fun minimizePanel() {
        if (isMinimized) return

        // Save current position
        val pp = panelParams
        if (pp != null) {
            savedX = pp.x
            savedY = pp.y
        }

        // Remove full panel
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
        panelParams = null

        // Show small icon
        showMinimizedIcon()
        isMinimized = true
    }

    private fun showMinimizedIcon() {
        val density = resources.displayMetrics.density
        val size = (36 * density).toInt()

        val iconView = TextView(this).apply {
            text = "📊"
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(0xCC222222.toInt())
        }

        minimizedParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        iconView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialTX = event.rawX
                    initialTY = event.rawY
                    initialPX = minimizedParams?.x ?: 0
                    initialPY = minimizedParams?.y ?: 0
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTX
                    val dy = event.rawY - initialTY
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) dragged = true
                    minimizedParams?.x = (initialPX + dx).toInt()
                    minimizedParams?.y = (initialPY + dy).toInt()
                    try { windowManager?.updateViewLayout(iconView, minimizedParams) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) restorePanel()
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(iconView, minimizedParams)
            minimizedView = iconView
        } catch (e: Exception) {
            Log.e(TAG, "添加最小化图标失败", e)
        }
    }

    private var initialTX = 0f
    private var initialTY = 0f
    private var initialPX = 0
    private var initialPY = 0
    private var dragged = false

    private fun restorePanel() {
        if (!isMinimized) return
        removeMinimizedIcon()
        isMinimized = false

        // Recreate and show full panel at saved position
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.dashboard_overlay, null)
        setupViews()

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = savedX
            y = savedY
        }
        windowManager?.addView(overlayView, panelParams)
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
        if (overlayView == null) return
        handler.post {
            val btn = overlayView?.findViewById<View>(R.id.btnPause) as? TextView ?: return@post
            if (DriverDataStore.timerStopped) {
                btn.text = "▶ 开始计时"
                btn.setBackgroundColor(0xFF4CAF50.toInt())
            } else {
                btn.text = "⏹ 停止计时"
                btn.setBackgroundColor(0xFFFF9800.toInt())
            }
        }
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
