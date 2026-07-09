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
        Thread.sleep(1500)

        // 2. Check for 4 required keywords, navigate back if not found
        var attempts = 0
        while (attempts < 10) {
            val root = try { rootInActiveWindow } catch (e: Exception) { null }
            if (root == null) {
                Thread.sleep(500)
                attempts++
                continue
            }
            val allText = collectAllText(root)
            root.recycle()

            if (REQUIRED_KEYWORDS.all { allText.contains(it) }) {
                // Found all keywords — click home tab
                clickHomeTab()
                Thread.sleep(800)
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

            // Keywords not found — press back
            performGlobalAction(GLOBAL_ACTION_BACK)
            Thread.sleep(1000)
            attempts++
        }
        ConfigManager.appendLog("DASH", "导航失败，${attempts}次重试后未找到首页")
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
            // Find the node with text="接单数" (the title of the order count card)
            val titleNodes = root.findAccessibilityNodeInfosByText("接单数")
            for (titleNode in titleNodes) {
                val parent = titleNode.parent ?: continue
                try {
                    // Within the same announce_all_main_info_group, find announce_main_info_value
                    for (i in 0 until parent.childCount) {
                        val child = parent.getChild(i) ?: continue
                        try {
                            val resId = child.viewIdResourceName ?: ""
                            if (resId.endsWith("/announce_main_info_value")) {
                                val text = child.text?.toString() ?: ""
                                val cleaned = text.replace(Regex("[^0-9]"), "")
                                val count = cleaned.toIntOrNull()
                                if (count != null && count >= 0) {
                                    return count
                                }
                            }
                        } catch (_: Exception) {}
                    }
                } finally {
                    parent.recycle()
                }
            }
            // Fallback: find all announce_main_info_value nodes and match by adjacent title
            val allValueNodes = root.findAccessibilityNodeInfosByViewId("announce_main_info_value")
            for (valueNode in allValueNodes) {
                val text = valueNode.text?.toString() ?: ""
                val parent = valueNode.parent ?: continue
                try {
                    for (i in 0 until parent.childCount) {
                        val sibling = parent.getChild(i) ?: continue
                        try {
                            if (sibling.text?.toString() == "接单数") {
                                val cleaned = text.replace(Regex("[^0-9]"), "")
                                val count = cleaned.toIntOrNull()
                                if (count != null && count >= 0) {
                                    return count
                                }
                            }
                        } catch (_: Exception) {}
                    }
                } finally {
                    parent.recycle()
                }
            }
        } finally {
            root.recycle()
        }
        return -1
    }
}
