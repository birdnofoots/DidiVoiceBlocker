package com.didi.voiceblocker

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    companion object {
        const val TAG = "MainActivity"
    }

    private lateinit var statusText: TextView
    private lateinit var statusIndicator: TextView
    private lateinit var masterSwitch: androidx.appcompat.widget.SwitchCompat
    private lateinit var tabWhitelist: Button
    private lateinit var tabRecords: Button
    private lateinit var tabStats: Button
    private lateinit var panelWhitelist: LinearLayout
    private lateinit var panelRecords: LinearLayout
    private lateinit var panelStats: LinearLayout

    private var currentTab = 0
    private val handler = Handler(Looper.getMainLooper())

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                SmartVoiceBlocker.ACTION_MUTE_STATE_CHANGED,
                AudioMonitorService.ACTION_PLAYBACK_STATE_CHANGED -> updateStatus()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ConfigManager.init(this)

        statusText = findViewById(R.id.statusText)
        statusIndicator = findViewById(R.id.statusIndicator)
        masterSwitch = findViewById(R.id.masterSwitch)
        tabWhitelist = findViewById(R.id.tabWhitelist)
        tabRecords = findViewById(R.id.tabRecords)
        tabStats = findViewById(R.id.tabStats)
        panelWhitelist = findViewById(R.id.panelWhitelist)
        panelRecords = findViewById(R.id.panelRecords)
        panelStats = findViewById(R.id.panelStats)

        masterSwitch.isChecked = ConfigManager.enabled
        masterSwitch.setOnCheckedChangeListener { _, checked ->
            ConfigManager.enabled = checked
            ConfigManager.save()
            updateStatus()
        }

        tabWhitelist.setOnClickListener { switchTab(0) }
        tabRecords.setOnClickListener { switchTab(1) }
        tabStats.setOnClickListener { switchTab(2) }

        setupWhitelistPanel()
        setupRecordsPanel()
        setupStatsPanel()

        findViewById<Button>(R.id.btnStartBall).setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                startService(Intent(this, FloatingBallService::class.java))
            } else {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName"))
                startActivity(intent)
            }
        }

        findViewById<Button>(R.id.btnStopBall).setOnClickListener {
            stopService(Intent(this, FloatingBallService::class.java))
        }

        checkPermissions()
        switchTab(0)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(SmartVoiceBlocker.ACTION_MUTE_STATE_CHANGED)
            addAction(AudioMonitorService.ACTION_PLAYBACK_STATE_CHANGED)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(stateReceiver, filter)
        handler.postDelayed({ updateStatus() }, 500)
    }

    override fun onPause() {
        super.onPause()
        try { LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
    }

    private fun switchTab(index: Int) {
        currentTab = index
        val tabs = listOf(tabWhitelist, tabRecords, tabStats)
        val panels = listOf(panelWhitelist, panelRecords, panelStats)
        tabs.forEachIndexed { i, btn ->
            btn.setBackgroundColor(if (i == index) 0xFF2196F3.toInt() else 0xFF333333.toInt())
        }
        panels.forEachIndexed { i, panel ->
            panel.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        if (index == 1) refreshRecords()
        if (index == 2) refreshStats()
    }

    private fun setupWhitelistPanel() {
        setupAddRemoveListContainer(
            findViewById(R.id.inputAddText), findViewById(R.id.btnAddText),
            findViewById(R.id.listTextWhitelistContainer), ConfigManager.allowTexts.toMutableSet(),
            { ConfigManager.addText(it) }, { ConfigManager.removeText(it) }
        )
        setupAddRemoveListContainer(
            findViewById(R.id.inputAddResId), findViewById(R.id.btnAddResId),
            findViewById(R.id.listResIdWhitelistContainer), ConfigManager.allowResourceIds.toMutableSet(),
            { ConfigManager.addResourceId(it) }, { ConfigManager.removeResourceId(it) }
        )
        setupAddRemoveListContainer(
            findViewById(R.id.inputAddBlock), findViewById(R.id.btnAddBlock),
            findViewById(R.id.listBlockHintsContainer), ConfigManager.blockHints.toMutableSet(),
            { ConfigManager.addBlockHint(it) }, { ConfigManager.removeBlockHint(it) }
        )

        findViewById<Button>(R.id.btnResetDefaults).setOnClickListener {
            ConfigManager.resetToDefaults()
            setupWhitelistPanel()
        }
    }

    private fun setupAddRemoveListContainer(
        input: EditText, btnAdd: Button, container: LinearLayout,
        items: MutableSet<String>,
        onAdd: (String) -> Unit, onRemove: (String) -> Unit
    ) {
        fun refresh() {
            container.removeAllViews()
            for (item in items.toList().sorted()) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                val tv = TextView(this).apply {
                    text = item
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 13f
                    setPadding(8, 8, 8, 8)
                }
                val delBtn = Button(this).apply {
                    text = "×"
                    textSize = 14f
                    setTextColor(0xFFFF5252.toInt())
                    setBackgroundColor(0x00000000)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setOnClickListener {
                        onRemove(item)
                        items.remove(item)
                        refresh()
                    }
                }
                row.addView(tv)
                row.addView(delBtn)
                container.addView(row)
            }
        }

        refresh()

        btnAdd.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                onAdd(text)
                items.add(text)
                refresh()
                input.text.clear()
            }
        }
    }

    private fun setupRecordsPanel() {
        refreshRecords()
        findViewById<Button>(R.id.btnClearRecords).setOnClickListener {
            ConfigManager.clearPlaybackRecords()
            refreshRecords()
            Toast.makeText(this, "已清空记录", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshRecords() {
        val recordsView = findViewById<TextView>(R.id.recordsView) ?: return
        val records = ConfigManager.getPlaybackRecordsList()
        if (records.isEmpty()) {
            recordsView.text = "(暂无记录)"
            return
        }
        val timeSdf = SimpleDateFormat("HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        for (r in records) {
            val startStr = timeSdf.format(Date(r.startTime))
            val endStr = timeSdf.format(Date(r.endTime))
            val durationMs = r.duration
            val durationSec = if (durationMs >= 1000) {
                String.format(Locale.US, "%.3f秒 (%dms)", durationMs / 1000.0, durationMs)
            } else {
                "${durationMs}ms"
            }
            val status = if (r.wasMuted) "🔇 静音" else "🔊 放行"
            sb.appendLine("[$startStr] ▶ 播报开始 @ ${r.startTime}ms")
            sb.appendLine("[$endStr] ■ 播报结束 @ ${r.endTime}ms | 持续 $durationSec | $status")
            if (r.allowReason.isNotEmpty() && r.allowReason != "待定") {
                sb.appendLine("    原因: ${r.allowReason}")
            }
            sb.appendLine()
        }
        recordsView.text = sb.toString()
        recordsView.post { (recordsView.parent as? ScrollView)?.scrollTo(0, recordsView.height) }
    }

    private fun setupStatsPanel() {
        refreshStats()
        findViewById<Button>(R.id.btnClearStats).setOnClickListener {
            ConfigManager.clearStats()
            refreshStats()
        }
        findViewById<Button>(R.id.btnViewLog).setOnClickListener {
            val log = ConfigManager.readLog()
            val statsView = findViewById<TextView>(R.id.statsText)
            if (log.isEmpty()) {
                statsView.text = "(日志为空)"
            } else {
                statsView.text = log
                statsView.post { (statsView.parent as? ScrollView)?.scrollTo(0, statsView.height) }
            }
            Toast.makeText(this, "日志路径: ${ConfigManager.getLogPath()}", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshStats() {
        val stats = ConfigManager.getStats()
        val tv = findViewById<TextView>(R.id.statsText) ?: return
        val sb = StringBuilder()

        // System status
        sb.appendLine("=== 系统状态 ===")
        sb.appendLine("无障碍服务: ${if (isAccessibilityEnabled()) "✅ 已开启" else "❌ 未开启"}")
        sb.appendLine("悬浮窗权限: ${if (Settings.canDrawOverlays(this)) "✅ 已授权" else "❌ 未授权"}")
        sb.appendLine("总开关: ${if (ConfigManager.enabled) "✅ 启用" else "❌ 禁用"}")
        sb.appendLine()

        // Playback stats
        val records = ConfigManager.getPlaybackRecordsList()
        val totalMuted = records.count { it.wasMuted }
        val totalAllowed = records.count { !it.wasMuted }
        sb.appendLine("=== 播报统计 ===")
        sb.appendLine("总播报次数: ${records.size}")
        sb.appendLine("静音次数: $totalMuted")
        sb.appendLine("放行次数: $totalAllowed")
        sb.appendLine()

        // Legacy stats
        sb.appendLine("=== 切换统计 ===")
        sb.appendLine("总切换次数: ${stats.optInt("total", 0)}")
        sb.appendLine("静音次数: ${stats.optInt("mute_count", 0)}")
        sb.appendLine("解静音次数: ${stats.optInt("unmute_count", 0)}")
        sb.appendLine()
        sb.appendLine("--- 最近事件 ---")

        val events = stats.optJSONArray("events")
        if (events != null && events.length() > 0) {
            val start = maxOf(0, events.length() - 10)
            for (i in start until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                val time = event.optLong("time", 0)
                val type = event.optString("type", "?")
                val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
                val timeStr = if (time > 0) sdf.format(Date(time)) else "?"
                sb.appendLine("[$timeStr] ${if (type == "mute") "🔇 静音" else "🔊 放行"}")
            }
        } else {
            sb.appendLine("(暂无记录)")
        }

        tv.text = sb.toString()
    }

    private fun updateStatus() {
        val svc = SmartVoiceBlocker.instance
        val status = buildString {
            if (!ConfigManager.enabled) {
                append("已暂停")
            } else if (svc != null) {
                val muted = svc.getMutedState()
                if (muted) append("🔇 静音中") else append("🔊 放行中")
                val activity = svc.getLastActivity()
                if (activity.isNotEmpty()) {
                    val short = activity.substringAfterLast(".")
                    append(" | $short")
                }
            } else {
                append("服务未连接")
            }
        }
        statusIndicator.text = status
        statusIndicator.setTextColor(
            if (!ConfigManager.enabled) 0xFFF44336.toInt()
            else if (svc?.getMutedState() == true) 0xFF9E9E9E.toInt()
            else 0xFF4CAF50.toInt()
        )
        statusText.text = "Didi Voice Blocker v1.0 - ${if (ConfigManager.enabled) "已启用" else "已暂停"}"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
            .any { it.id.contains(packageName) }
    }

    private fun checkPermissions() {
        val missing = mutableListOf<String>()
        if (!isAccessibilityEnabled()) missing.add("辅助功能")
        if (!Settings.canDrawOverlays(this)) missing.add("悬浮窗")

        val guideCard = findViewById<LinearLayout>(R.id.guideCard)
        val guideText = findViewById<TextView>(R.id.guideText)
        if (missing.isNotEmpty()) {
            guideCard.visibility = View.VISIBLE
            guideText.text = "需要授权: ${missing.joinToString(", ")}"
            findViewById<Button>(R.id.btnGrantAccess).setOnClickListener {
                if (!isAccessibilityEnabled()) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } else if (!Settings.canDrawOverlays(this)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")))
                }
            }
        } else {
            guideCard.visibility = View.GONE
        }
    }
}
