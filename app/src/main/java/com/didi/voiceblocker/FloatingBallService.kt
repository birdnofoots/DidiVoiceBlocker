package com.didi.voiceblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class FloatingBallService : Service() {
    companion object {
        const val TAG = "FloatingBall"
        const val CHANNEL_ID = "blocker_floating"
        const val NOTIFICATION_ID = 1001
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isMuted = false
    private var isPlaying = false
    private lateinit var metrics: DisplayMetrics

    // Sub-menu views managed like DiDiAudioMonitor FloatingButtonService
    private val subMenuViews = mutableListOf<View>()
    private var isSubMenuOpen = false
    private val handler = Handler(Looper.getMainLooper())

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioMonitorService.ACTION_PLAYBACK_STATE_CHANGED -> {
                    isPlaying = intent.getBooleanExtra(AudioMonitorService.EXTRA_IS_PLAYING, false)
                    isMuted = intent.getBooleanExtra(AudioMonitorService.EXTRA_IS_MUTED, false)
                    updateBallIcon()
                }
                SmartVoiceBlocker.ACTION_MUTE_STATE_CHANGED -> {
                    isMuted = intent.getBooleanExtra(SmartVoiceBlocker.EXTRA_IS_MUTED, false)
                    updateBallIcon()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ConfigManager.init(this)
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // Also start AudioMonitorService if not already running
        if (ConfigManager.enabled) {
            val monitorIntent = Intent(this, AudioMonitorService::class.java)
            startForegroundService(monitorIntent)
            Log.d(TAG, "Started AudioMonitorService from FloatingBallService")
        }

        val filter = IntentFilter().apply {
            addAction(AudioMonitorService.ACTION_PLAYBACK_STATE_CHANGED)
            addAction(SmartVoiceBlocker.ACTION_MUTE_STATE_CHANGED)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(stateReceiver, filter)

        createFloatingView()
        Log.d(TAG, "Floating ball started")
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver)
        closeSubMenu()
        floatingView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        super.onDestroy()
        Log.d(TAG, "Floating ball stopped")
    }

    private fun createFloatingView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getMetrics(metrics)

        val density = resources.displayMetrics.density
        val size = (50 * density).toInt()
        val startX = metrics.widthPixels - size - (20 * density).toInt()
        val startY = (200 * density).toInt()

        layoutParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY
        }

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_ball, null)
        updateBallIcon()

        var downX = 0f
        var downY = 0f
        var downRawX = 0f
        var downRawY = 0f
        var isDragging = false

        floatingView!!.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDragging = true
                    layoutParams?.x = (downRawX - downX + dx).toInt()
                    layoutParams?.y = (downRawY - downY + dy).toInt()
                    try { windowManager?.updateViewLayout(floatingView, layoutParams) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        if (isSubMenuOpen) closeSubMenu() else openSubMenu()
                    }
                    true
                }
                else -> false
            }
        }

        try { windowManager?.addView(floatingView, layoutParams) } catch (e: Exception) {
            Log.e(TAG, "Failed to add view", e)
            stopSelf()
        }
    }

    private fun openSubMenu() {
        if (floatingView == null || isSubMenuOpen) return
        isSubMenuOpen = true

        val density = resources.displayMetrics.density
        val radiusPx = (90 * density).toInt()
        val halfItem = (22 * density).toInt()
        val itemSize = (44 * density).toInt()

        val densityD = resources.displayMetrics.density
        val ballSizePx = (50 * densityD).toInt()
        val centerX = (layoutParams?.x ?: 0) + ballSizePx / 2
        val centerY = (layoutParams?.y ?: 0) + ballSizePx / 2

        val screenWidth = metrics.widthPixels
        val isOnLeft = centerX < screenWidth / 2

        val menuItems = listOf(
            Triple("📋", "白名单管理") { openMainActivity("whitelist") },
            Triple("📊", "播报记录") { openMainActivity("records") },
            Triple(if (ConfigManager.enabled) "⏸ 关" else "▶ 开", "总开关") {
                ConfigManager.enabled = !ConfigManager.enabled
                ConfigManager.save()
                updateBallIcon()
                closeSubMenu()
            },
            Triple("❌", "关闭悬浮球") { stopSelf() }
        )

        val count = menuItems.size
        val startAngle: Float
        val endAngle: Float
        if (isOnLeft) {
            startAngle = -90f
            endAngle = 90f
        } else {
            startAngle = 90f
            endAngle = 270f
        }

        menuItems.forEachIndexed { index, (emoji, _, action) ->
            val angleDeg: Float = if (count == 1) {
                (startAngle + endAngle) / 2f
            } else {
                startAngle + (endAngle - startAngle) * index / (count - 1)
            }
            val angleRad = Math.toRadians(angleDeg.toDouble())

            val itemX = centerX + (radiusPx * Math.cos(angleRad)).toInt() - halfItem
            val itemY = centerY + (radiusPx * Math.sin(angleRad)).toInt() - halfItem

            val itemView = TextView(this).apply {
                text = emoji
                textSize = 18f
                gravity = Gravity.CENTER
                setBackgroundColor(0xEE2A2A2A.toInt())
                setPadding((6 * density).toInt(), (6 * density).toInt(),
                    (6 * density).toInt(), (6 * density).toInt())
                elevation = 16f
                setOnClickListener { action() }
            }

            val params = WindowManager.LayoutParams(
                itemSize, itemSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = itemX
                this.y = itemY
            }

            try {
                windowManager?.addView(itemView, params)
                subMenuViews.add(itemView)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add submenu item", e)
            }
        }
    }

    private fun closeSubMenu() {
        isSubMenuOpen = false
        for (v in subMenuViews) {
            try { windowManager?.removeView(v) } catch (_: Exception) {}
        }
        subMenuViews.clear()
    }

    private fun getCurrentStatusText(): String {
        return when {
            !ConfigManager.enabled -> "已暂停"
            isPlaying && !isMuted -> "🔊 播报中"
            isPlaying && isMuted -> "🔇 播报中(已静音)"
            isMuted -> "🔇 静音"
            else -> "🔊 就绪"
        }
    }

    private fun openMainActivity(mode: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("mode", mode)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun updateBallIcon() {
        val icon = floatingView?.findViewById<ImageView>(R.id.ball_icon) ?: return
        if (!ConfigManager.enabled) {
            icon.setBackgroundColor(0xFFF44336.toInt())
            icon.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        } else if (isPlaying) {
            icon.setBackgroundColor(if (isMuted) 0xFFFF9800.toInt() else 0xFF2196F3.toInt())
            icon.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
        } else if (isMuted) {
            icon.setBackgroundColor(0xFF9E9E9E.toInt())
            icon.setImageResource(android.R.drawable.ic_lock_silent_mode)
        } else {
            icon.setBackgroundColor(0xFF4CAF50.toInt())
            icon.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "悬浮球服务",
                NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, FloatingBallService::class.java)
        val stopPending = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("滴滴静音器")
            .setContentText("悬浮球工作中")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭", stopPending)
            .setOngoing(true)
            .build()
    }
}
