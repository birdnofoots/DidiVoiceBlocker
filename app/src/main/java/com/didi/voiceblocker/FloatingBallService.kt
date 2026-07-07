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
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
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
    private var popupWindow: PopupWindow? = null
    private var isMuted = false
    private var isPlaying = false
    private lateinit var metrics: DisplayMetrics

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioMonitorService.ACTION_PLAYBACK_STATE_CHANGED -> {
                    isPlaying = intent.getBooleanExtra(AudioMonitorService.EXTRA_IS_PLAYING, false)
                    isMuted = intent.getBooleanExtra(AudioMonitorService.EXTRA_IS_MUTED, false)
                    updateBallIcon()
                }
                SmartVoiceBlocker.ACTION_MUTE_STATE_CHANGED -> {
                    // Legacy support
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
        floatingView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        popupWindow?.dismiss()
        super.onDestroy()
        Log.d(TAG, "Floating ball stopped")
    }

    private fun createFloatingView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getMetrics(metrics)

        val size = (50 * resources.displayMetrics.density).toInt()
        val startX = metrics.widthPixels - size - (20 * resources.displayMetrics.density).toInt()
        val startY = 200

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
        val ballIcon = floatingView!!.findViewById<ImageView>(R.id.ball_icon)
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
                    if (!isDragging) showPopup()
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

    private fun showPopup() {
        popupWindow?.dismiss()

        // Semi-circular menu layout
        val menuItems = listOf(
            Triple("\uD83D\uDD0D 当前: ${getCurrentStatusText()}", "status", {
                android.widget.Toast.makeText(this,
                    "状态: ${getCurrentStatusText()}", android.widget.Toast.LENGTH_SHORT).show()
                popupWindow?.dismiss()
                Unit
            }),
            Triple("\uD83D\uDCCB 白名单管理", "whitelist", { openMainActivity("whitelist"); Unit }),
            Triple("\uD83D\uDCCA 播报记录", "records", { openMainActivity("records"); Unit }),
            Triple("⚙️ 总开关: ${if (ConfigManager.enabled) "开" else "关"}", "toggle", {
                ConfigManager.enabled = !ConfigManager.enabled
                ConfigManager.save()
                updateBallIcon()
                popupWindow?.dismiss()
                Unit
            }),
            Triple("❌ 关闭悬浮球", "exit", { stopSelf(); Unit })
        )

        val contentView = createSemiCircleMenu(menuItems)

        popupWindow = PopupWindow(contentView,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT, true).apply {
            isOutsideTouchable = true
            elevation = 0f
            showAtLocation(floatingView, Gravity.CENTER, 0, 0)
        }
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

    private fun createSemiCircleMenu(items: List<Triple<String, String, () -> Unit>>): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 32)
            setBackgroundColor(0xEE1E1E1E.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val density = resources.displayMetrics.density
        val radius = (180 * density).toInt()  // Semi-circle radius
        val ballCenterX = layoutParams?.x?.toInt() ?: (metrics.widthPixels / 2)
        val ballCenterY = layoutParams?.y?.toInt() ?: 200

        val arcContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        items.forEachIndexed { index, (text, _, action) ->
            // Calculate position in semi-circle arc
            val itemCount = items.size
            val angle = Math.PI * index / (itemCount - 1).coerceAtLeast(1)  // 0 to PI
            val offsetX = (radius * Math.cos(angle) - radius).toInt()
            val offsetY = -(radius * Math.sin(angle)).toInt()  // Negative = above

            val tv = TextView(this@FloatingBallService).apply {
                this.text = text
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                setPadding((16 * density).toInt(), (12 * density).toInt(),
                    (16 * density).toInt(), (12 * density).toInt())
                setOnClickListener { action() }
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF333333.toInt())
                    cornerRadius = 8 * density
                }
            }

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(
                    (ballCenterX + offsetX).coerceIn(20, metrics.widthPixels - 200),
                    (ballCenterY + offsetY - 300).coerceIn(0, metrics.heightPixels - 200),
                    0, 0
                )
            }

            val itemWrapper = LinearLayout(this@FloatingBallService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(tv)
            }

            arcContainer.addView(itemWrapper, params)
        }

        container.addView(arcContainer)

        // Semi-transparent overlay to dismiss
        val overlay = View(this).apply {
            setBackgroundColor(0x00000000)
            setOnClickListener { popupWindow?.dismiss() }
        }
        val overlayParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        container.addView(overlay, 0, overlayParams)

        return container
    }



    private fun openMainActivity(mode: String) {
        popupWindow?.dismiss()
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
            // Playing state - blue with appropriate icon
            if (isMuted) {
                icon.setBackgroundColor(0xFFFF9800.toInt())  // Orange = playing but muted
            } else {
                icon.setBackgroundColor(0xFF2196F3.toInt())  // Blue = playing
            }
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
