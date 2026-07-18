package com.didi.voiceblocker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class DashboardAccessibilityService : AccessibilityService() {
    companion object {
        const val TAG = "DashA11y"
        const val ACTION_REFRESH_ORDERS = "com.didi.voiceblocker.REFRESH_ORDERS"
        private const val DIDI_PKG = "com.sdu.didi.gsui"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // 自动提取订单数: 检测 DIDI 回到首页时,仅在数量变化时更新
    private var lastOrderCount = -1
    private var lastAutoReadTime = 0L
    private val AUTO_READ_DEBOUNCE_MS = 3000L

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Thread { refreshOrders() }.start()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            packageNames = arrayOf(DIDI_PKG)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(
            refreshReceiver, IntentFilter(ACTION_REFRESH_ORDERS)
        )
        Log.d(TAG, "DashboardAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val et = event.eventType
        // 两类事件都处理: Activity 切换 + 页内内容变更(如 tab 切换)
        if (et != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            et != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        // 防抖
        val now = System.currentTimeMillis()
        if (now - lastAutoReadTime < AUTO_READ_DEBOUNCE_MS) return
        lastAutoReadTime = now

        // 延迟到下一帧读,避免主线程卡顿
        mainHandler.post {
            // 检查是否在 DIDI 主页
            if (!isOnDidiMainScreen()) return@post

            // 读订单数,仅变化时更新持久化
            val count = readOrderCount()
            if (count >= 0 && count != lastOrderCount) {
                lastOrderCount = count
                DriverDataStore.updateOrderCount(count)
                Log.d(TAG, "Auto order update: $count")
                ConfigManager.appendLog("DASH", "自动更新订单数=$count")
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(refreshReceiver)
        super.onDestroy()
    }

    private fun refreshOrders() {
        // 1. Pull DiDi to foreground
        val intent = packageManager.getLaunchIntentForPackage(DIDI_PKG)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }
        Thread.sleep(2000)

        // 2. Navigate to main screen and read
        navigateAndRead(0)
    }

    private fun navigateAndRead(attempt: Int) {
        if (attempt >= 5) {
            Log.w(TAG, "Navigation exhausted, trying to read anyway")
            val count = readOrderCount()
            finishWithCount(count)
            return
        }

        if (isOnDidiMainScreen()) {
            val count = readOrderCount()
            finishWithCount(count)
            return
        }

        if (hasTabBar()) {
            clickHomeTab()
            mainHandler.postDelayed({
                if (isOnDidiMainScreen()) {
                    val count = readOrderCount()
                    finishWithCount(count)
                } else {
                    try {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    } catch (_: Exception) {}
                    mainHandler.postDelayed({ navigateAndRead(attempt + 1) }, 1500)
                }
            }, 2000)
        } else {
            if (isOnDidiMainScreen()) {
                val count = readOrderCount()
                finishWithCount(count)
                return
            }
            try {
                performGlobalAction(GLOBAL_ACTION_BACK)
            } catch (_: Exception) {}
            mainHandler.postDelayed({ navigateAndRead(attempt + 1) }, 1500)
        }
    }

    private fun finishWithCount(count: Int) {
        if (count >= 0) {
            lastOrderCount = count
            DriverDataStore.updateOrderCount(count)
            Log.d(TAG, "Orders updated: $count")
            ConfigManager.appendLog("DASH", "订单数=$count")
        } else {
            ConfigManager.appendLog("DASH", "读取接单数失败")
        }

        // 当天首次读订单时,顺便检查出车拍照
        if (!DriverPhotoStore.photoCheckedToday) {
            mainHandler.postDelayed({ checkDriverPhoto() }, 2000)
        }
    }

    private fun isOnDidiMainScreen(): Boolean {
        val root = try { rootInActiveWindow } catch (e: Exception) { null } ?: return false
        try {
            val texts = collectAllText(root)
            return texts.contains("接单数") ||
                texts.contains("听单中") ||
                texts.contains("今日流水") ||
                texts.contains("口碑值")
        } finally {
            root.recycle()
        }
    }

    private fun hasTabBar(): Boolean {
        val root = try { rootInActiveWindow } catch (e: Exception) { null } ?: return false
        try {
            val texts = collectAllText(root)
            return texts.contains("首页") || texts.contains("找单")
        } finally {
            root.recycle()
        }
    }

    private fun clickHomeTab() {
        val root = try { rootInActiveWindow } catch (e: Exception) { null } ?: return
        try {
            val nodes = root.findAccessibilityNodeInfosByText("首页")
            for (node in nodes) {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return
                }
                val parent = node.parent
                if (parent != null && parent.isClickable) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return
                }
            }
        } finally {
            root.recycle()
        }
    }

    private fun readOrderCount(): Int {
        val root = try { rootInActiveWindow } catch (e: Exception) { null } ?: return -1
        try {
            // Find "接单数" text → get parent → find sibling with numeric text
            val nodes = root.findAccessibilityNodeInfosByText("接单数")
            if (nodes.isNotEmpty()) {
                val jiedanNode = nodes[0]
                val parent = jiedanNode.parent
                if (parent != null) {
                    try {
                        for (i in 0 until parent.childCount) {
                            val child = parent.getChild(i) ?: continue
                            try {
                                val text = child.text?.toString() ?: ""
                                if (text.matches(Regex("^\\d+$"))) {
                                    return text.toIntOrNull() ?: -1
                                }
                            } finally {
                                child.recycle()
                            }
                        }
                    } finally {
                        parent.recycle()
                    }
                }
                jiedanNode.recycle()
            }
        } catch (_: Exception) {}
        finally {
            root.recycle()
        }
        return -1
    }

    private fun collectAllText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        collectTextRecursive(node, sb)
        return sb.toString()
    }

    private fun collectTextRecursive(node: AccessibilityNodeInfo, sb: StringBuilder) {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if (text.isNotEmpty()) sb.append(text).append(" ")
        if (desc.isNotEmpty()) sb.append(desc).append(" ")
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
            try { collectTextRecursive(child, sb) } finally { child.recycle() }
        }
    }

    // 导航到全部工具 → 出车拍照 → 读"审核中" → 返回
    private fun checkDriverPhoto() {
        if (DriverPhotoStore.photoCheckedToday) return

        try {
            // Step 1: 点"全部工具"
            val root = rootInActiveWindow ?: return
            var btn = findClickableByText(root, "全部工具")
            if (btn == null) {
                root.recycle()
                return
            }
            btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            btn.recycle()
            root.recycle()
            Thread.sleep(1500)

            // Step 2: 点"出车拍照"
            val root2 = rootInActiveWindow ?: return
            btn = findClickableByText(root2, "出车拍照")
            if (btn == null) {
                root2.recycle()
                return
            }
            btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            btn.recycle()
            root2.recycle()
            Thread.sleep(2000)

            // Step 3: 读页面是否有"审核中"
            val root3 = rootInActiveWindow ?: return
            val texts = collectAllText(root3)
            val completed = texts.contains("审核中")
            root3.recycle()

            DriverPhotoStore.setPhotoCompleted(completed)
            Log.d(TAG, "Photo check: completed=$completed")
            ConfigManager.appendLog("DASH", "出车拍照=${if (completed) "已拍照" else "未完成"}")

            // Step 4: 返回
            performGlobalAction(GLOBAL_ACTION_BACK)
            Thread.sleep(800)
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (e: Exception) {
            Log.e(TAG, "Photo check failed", e)
        }
    }

    private fun findClickableByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodes = node.findAccessibilityNodeInfosByText(text)
        for (n in nodes) {
            if (n.isClickable) return n
            n.recycle()
        }
        return null
    }
}
