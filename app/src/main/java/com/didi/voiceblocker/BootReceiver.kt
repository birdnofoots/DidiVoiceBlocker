package com.didi.voiceblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        ConfigManager.init(context)
        if (!ConfigManager.enabled) return

        // Start AudioMonitorService
        val monitorIntent = Intent(context, AudioMonitorService::class.java)
        context.startForegroundService(monitorIntent)
        Log.d("BootReceiver", "Started AudioMonitorService")

        // Start FloatingBallService if overlay permission granted
        if (Settings.canDrawOverlays(context)) {
            val ballIntent = Intent(context, FloatingBallService::class.java)
            context.startForegroundService(ballIntent)
            Log.d("BootReceiver", "Started FloatingBallService")
        }
    }
}
