package com.didi.voiceblocker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class DashboardAccessibilityService : AccessibilityService() {
    companion object {
        const val TAG = "DashA11y"
        const val ACTION_REFRESH_ORDERS = "com.didi.voiceblocker.REFRESH_ORDERS"
    private val REQUIRED_KEYWORDS = listOf("首页", "找单", "生活", "交流")
    private const val DIDI_PKG = "com.sdu.didi.gsui"
    private const val DIDI_RES_VALUE = "com.sdu.didi.gsui:id/announce_main_info_value"
    }

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
        Thread.sleep(2000) // Wait for page transition

        // 2. Keep pressing BACK until 4-tab page appears, then click 首页
        var attempts = 0
        while (attempts < 15) {
            val root = try { rootInActiveWindow } catch (e: Exception) { null }
            if (root == null) {
                Thread.sleep(500)
                attempts++
                continue
            }
            val allText = collectAllText(root)
            root.recycle()

            if (REQUIRED_KEYWORDS.all { allText.contains(it) }) {
                // Found 4-tab page — click 首页 tab
                clickHomeTab()
                Thread.sleep(1200) // Wait for home content to load
                val count = readOrderCount()
                if (count >= 0) {
                    DriverDataStore.updateOrderCount(count)
                    Log.d(TAG, "Orders updated: $count")
                    ConfigManager.appendLog("DASH", "订单数=$count")
                } else {
                    ConfigManager.appendLog("DASH", "读取接单数失败")
                }
                return
            }

            // Not on 4-tab page — press back
            performGlobalAction(GLOBAL_ACTION_BACK)
            Thread.sleep(1200)
            attempts++
        }
        ConfigManager.appendLog("DASH", "导航失败，未找到4-tab页面")
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
            val child = try { node.getChild(i) } catch (e: Exception) { null }
            if (child != null) {
                try { collectTextRecursive(child, sb) } finally { child.recycle() }
            }
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
            // Find all nodes with resource-id ending in announce_main_info_value
            val valueNodes = root.findAccessibilityNodeInfosByViewId(DIDI_RES_VALUE)
            for (valueNode in valueNodes) {
                val text = valueNode.text?.toString() ?: ""
                if (!text.matches(Regex("^\\d+$"))) continue
                // Check parent's children for "接单数" title
                val parent = valueNode.parent ?: continue
                for (i in 0 until parent.childCount) {
                    val sibling = parent.getChild(i) ?: continue
                    if (sibling.text?.toString() == "接单数") {
                        val count = text.toIntOrNull() ?: continue
                        sibling.recycle()
                        return count
                    }
                }
            }
        } finally {
            root.recycle()
        }
        return -1
    }
}
