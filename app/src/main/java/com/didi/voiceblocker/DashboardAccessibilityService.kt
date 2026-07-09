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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

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
            DriverDataStore.updateOrderCount(count)
            Log.d(TAG, "Orders updated: $count")
            ConfigManager.appendLog("DASH", "订单数=$count")
        } else {
            ConfigManager.appendLog("DASH", "读取接单数失败")
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
}
