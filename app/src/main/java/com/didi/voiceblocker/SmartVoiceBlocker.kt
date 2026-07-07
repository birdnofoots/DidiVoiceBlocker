package com.didi.voiceblocker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class SmartVoiceBlocker : AccessibilityService() {
    companion object {
        const val TAG = "SmartVoiceBlocker"
        const val ACTION_MUTE_STATE_CHANGED = "com.didi.voiceblocker.MUTE_STATE_CHANGED"
        const val ACTION_REQUEST_PAGE_CHECK = "com.didi.voiceblocker.REQUEST_PAGE_CHECK"
        const val EXTRA_IS_MUTED = "is_muted"
        private const val DIDI_PKG = "com.sdu.didi.gsui"
        private const val SCAN_TIMEOUT_MS = 5000L
        private const val DEBOUNCE_MS = 200L
        var instance: SmartVoiceBlocker? = null
            private set
    }

    private var audioManager: AudioManager? = null
    private val isMuted = AtomicBoolean(false)
    private var lastScanTime = 0L
    private var lastScanResult = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastActivityName = ""

    // Broadcast receiver for page check requests from AudioMonitorService
    private val pageCheckReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_REQUEST_PAGE_CHECK) {
                Log.d(TAG, "Received page check request")
                checkCurrentPageAndNotify()
            }
        }
    }

    val logFile by lazy { File(filesDir, "page_captures.log") }

    private fun logPageCapture(texts: List<String>, ids: List<String>,
                                hasWhitelist: Boolean, hasBlacklist: Boolean) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val entry = buildString {
            append("=== $ts ===\n")
            append("whitelist=$hasWhitelist blacklist=$hasBlacklist\n")
            append("texts: ${texts.filter { it.length < 50 }.distinct().take(30).joinToString(" | ")}\n")
            append("ids:   ${ids.distinct().take(30).joinToString(" | ")}\n\n")
        }
        logFile.appendText(entry)
        Log.d(TAG, entry.trim())
    }

    private fun scanWithDetails(node: AccessibilityNodeInfo, startTime: Long,
                                 texts: MutableList<String>, ids: MutableList<String>): Pair<Boolean, Boolean> {
        var hasWhitelist = false
        var hasBlacklist = false

        fun recurse(n: AccessibilityNodeInfo) {
            if (System.currentTimeMillis() - startTime > SCAN_TIMEOUT_MS) return

            val text = n.text?.toString() ?: ""
            val desc = n.contentDescription?.toString() ?: ""
            val resId = n.viewIdResourceName ?: ""

            if (text.isNotBlank()) texts.add(text)
            if (resId.isNotBlank()) ids.add(resId)

            for (hint in ConfigManager.blockHints) {
                if (text.contains(hint, true) || desc.contains(hint, true)) {
                    hasBlacklist = true
                    return
                }
            }
            for (allowed in ConfigManager.allowTexts) {
                if (text.contains(allowed, true) || desc.contains(allowed, true)) {
                    hasWhitelist = true
                }
            }
            for (allowedId in ConfigManager.allowResourceIds) {
                if (resId.contains(allowedId, true)) {
                    hasWhitelist = true
                }
            }

            for (i in 0 until n.childCount) {
                val child = try { n.getChild(i) } catch (e: Exception) { null }
                if (child != null) {
                    try { recurse(child) } finally { child.recycle() }
                }
            }
        }

        recurse(node)
        return Pair(hasWhitelist, hasBlacklist)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
            packageNames = arrayOf(DIDI_PKG)
        }

        ConfigManager.init(this)

        // Register for page check requests
        LocalBroadcastManager.getInstance(this).registerReceiver(
            pageCheckReceiver,
            IntentFilter(ACTION_REQUEST_PAGE_CHECK)
        )

        Log.d(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg != DIDI_PKG) return

        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val now = System.currentTimeMillis()
        if (now - lastScanTime < DEBOUNCE_MS) return
        lastScanTime = now

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = event.className?.toString()
            if (cls != null && cls != lastActivityName) {
                lastActivityName = cls
                Log.d(TAG, "Activity: $cls")
            }
        }

        // 不在这里触发扫描——扫描只在 AudioMonitorService 发来 ACTION_REQUEST_PAGE_CHECK 时由 pageCheckReceiver 触发
    }

    private fun checkCurrentPageAndNotify() {
        val root = try {
            rootInActiveWindow ?: run {
                Log.w(TAG, "No active window for page check")
                notifyPageResult(false)
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get root window", e)
            notifyPageResult(false)
            return
        }

        try {
            val allowsAudio = scanForAllowConditions(root)
            Log.d(TAG, "Page check result: allowsAudio=$allowsAudio")
            notifyPageResult(allowsAudio)
        } catch (e: Exception) {
            Log.e(TAG, "Page check failed", e)
            notifyPageResult(false)
        } finally {
            root.recycle()
        }
    }

    private fun notifyPageResult(allowsAudio: Boolean) {
        val intent = Intent(AudioMonitorService.ACTION_CHECK_PAGE_RESULT).apply {
            putExtra(AudioMonitorService.EXTRA_PAGE_ALLOWS_AUDIO, allowsAudio)
            setPackage(packageName)
        }
        try {
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send page check result", e)
        }
    }

    /**
     * Scan for all allow conditions (three lists):
     * 1. 预约单 page: "预约单", "专车舒适", "不抢" (allowTexts)
     * 2. 实时单 page (pickup phase): "实时单", "接乘客" (allowTexts)
     * 3. Resource IDs in allowResourceIds list
     * 4. 黑名单 blockHints → returns false immediately (mute)
     * 5. Notification simultaneous arrival (handled by AudioMonitorService)
     */
    private fun scanForAllowConditions(node: AccessibilityNodeInfo): Boolean {
        var foundBlacklist = false
        var foundWhitelist = false

        fun recurse(n: AccessibilityNodeInfo): Boolean {
            val text = n.text?.toString() ?: ""
            val desc = n.contentDescription?.toString() ?: ""
            val resId = n.viewIdResourceName ?: ""
            val combined = "$text $desc"

            // Check blacklist first — any hit means mute
            for (hint in ConfigManager.blockHints) {
                if (combined.contains(hint, true)) {
                    foundBlacklist = true
                    return true
                }
            }

            // Check allowTexts (预约单 + 实时单 keywords)
            for (allowed in ConfigManager.allowTexts) {
                if (combined.contains(allowed, true)) {
                    foundWhitelist = true
                }
            }

            // Check allowResourceIds
            for (allowedId in ConfigManager.allowResourceIds) {
                if (resId.contains(allowedId, true)) {
                    foundWhitelist = true
                }
            }

            for (i in 0 until n.childCount) {
                val child = try { n.getChild(i) } catch (e: Exception) { null }
                if (child != null) {
                    try {
                        if (recurse(child)) return true
                    } finally {
                        child.recycle()
                    }
                }
            }
            return false
        }

        recurse(node)

        // Blacklist hit → mute; whitelist hit → allow; neither → mute (default safe)
        val allows = !foundBlacklist && foundWhitelist
        Log.d(TAG, "scanForAllowConditions: blacklist=$foundBlacklist, whitelist=$foundWhitelist, allows=$allows")
        return allows
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        instance = null
        ensureUnmuted()
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(pageCheckReceiver)
        } catch (e: Exception) { }
        super.onDestroy()
    }

    private fun scanCurrentPage(): Boolean {
        val root = try {
            rootInActiveWindow ?: return false
        } catch (e: Exception) {
            return false
        }

        try {
            return scanNode(root, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "Scan error", e)
            return false
        } finally {
            root.recycle()
        }
    }

    private fun scanNode(node: AccessibilityNodeInfo, startTime: Long): Boolean {
        if (System.currentTimeMillis() - startTime > SCAN_TIMEOUT_MS) {
            Log.w(TAG, "Scan timeout")
            return false
        }

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val resId = node.viewIdResourceName ?: ""

        for (hint in ConfigManager.blockHints) {
            if (text.contains(hint, true) || desc.contains(hint, true)) {
                return false
            }
        }

        for (allowed in ConfigManager.allowTexts) {
            if (text.contains(allowed, true) || desc.contains(allowed, true)) {
                return true
            }
        }

        for (allowedId in ConfigManager.allowResourceIds) {
            if (resId.contains(allowedId, true)) {
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null }
            if (child != null) {
                try {
                    if (scanNode(child, startTime)) return true
                } finally {
                    child.recycle()
                }
            }
        }

        return false
    }

    private fun ensureMuted() {
        if (isMuted.compareAndSet(false, true)) {
            try {
                audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                ConfigManager.recordMute()
                broadcastState(true)
                Log.d(TAG, "MUTED")
            } catch (e: Exception) {
                Log.e(TAG, "Mute failed", e)
                isMuted.set(false)
            }
        }
    }

    private fun ensureUnmuted() {
        if (isMuted.compareAndSet(true, false)) {
            try {
                audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                ConfigManager.recordUnmute()
                broadcastState(false)
                Log.d(TAG, "UNMUTED")
            } catch (e: Exception) {
                Log.e(TAG, "Unmute failed", e)
            }
        }
    }

    private fun broadcastState(muted: Boolean) {
        val intent = Intent(ACTION_MUTE_STATE_CHANGED).apply {
            putExtra(EXTRA_IS_MUTED, muted)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    fun getMutedState(): Boolean = isMuted.get()
    fun getLastActivity(): String = lastActivityName
}
