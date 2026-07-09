package com.didi.voiceblocker

import android.app.*
import android.content.*
import android.media.*
import android.os.*
import android.util.*
import androidx.core.app.*
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class AudioMonitorService : Service() {
    companion object {
        const val TAG = "AudioMonitorService"
        const val CHANNEL_ID = "audio_monitor"
        const val NOTIFICATION_ID = 1002
        const val ACTION_PLAYBACK_STATE_CHANGED = "com.didi.voiceblocker.PLAYBACK_STATE_CHANGED"
        const val EXTRA_IS_PLAYING = "is_playing"
        const val EXTRA_IS_MUTED = "is_muted"
        const val ACTION_CHECK_PAGE_RESULT = "com.didi.voiceblocker.CHECK_PAGE_RESULT"
        const val EXTRA_PAGE_ALLOWS_AUDIO = "page_allows_audio"

        private val POSITION_CHECK_INTERVAL_MS: Long get() = ConfigManager.positionCheckIntervalMs
        private val SILENCE_THRESHOLD_MS: Long get() = ConfigManager.silenceThresholdMs
        private val MAX_PLAYBACK_DURATION_MS: Long get() = ConfigManager.maxPlaybackDurationMs
    }

    private var audioManager: AudioManager? = null
    private var isMuted = false
    private var isPlaying = false

    // Playback state tracking
    private var playbackStartTime = 0L
    private var lastActivityTime = 0L
    private var positionStableStartTime = 0L
    private var audioTriggerTime = 0L
    private var playbackIdCounter = 0

    // Detection state
    private enum class State { IDLE, PLAYING }
    private var state = State.IDLE

    private val handler = Handler(Looper.getMainLooper())

    // Page check state
    private var pendingPageCheck = false
    private var pageCheckStartTime = 0L

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>?) {
            configs ?: return
            handlePlaybackConfigs(configs)
        }
    }

    private fun handlePlaybackConfigs(configs: List<AudioPlaybackConfiguration>) {
        ConfigManager.init(this)

        val usageSet = configs.map { it.audioAttributes.usage }.distinct()
        ConfigManager.appendLog("AMS", "callback: ${configs.size} configs, usages=$usageSet")

        // 详细记录每条音频配置：usage + contentType + flags
        for (config in configs) {
            val attrs = config.audioAttributes
            ConfigManager.appendLog("AMS", "  cfg: usage=${attrs.usage} content=${attrs.contentType} flags=${attrs.flags}")
        }

        // Filter for relevant audio channels (media, voice communication, alarm)
        val relevantConfigs = configs.filter { config ->
            val usage = config.audioAttributes.usage
            usage == AudioAttributes.USAGE_MEDIA ||
                    usage == AudioAttributes.USAGE_VOICE_COMMUNICATION ||
                    usage == AudioAttributes.USAGE_ALARM
        }

        if (relevantConfigs.isEmpty()) {
            ConfigManager.appendLog("AMS", "no relevant configs, relevantUsages=${usageSet}")
            if (state == State.PLAYING) {
                checkForSilence()
            }
            return
        }

        // We have active audio - this counts as "position changing" activity
        lastActivityTime = System.currentTimeMillis()
        ConfigManager.appendLog("AMS", "relevant=${relevantConfigs.size}, state=$state")

        if (state == State.IDLE) {
            startPlayback()
        } else {
            // PLAYING state - reset stability timer
            positionStableStartTime = 0L
        }
    }

    private fun checkForSilence() {
        if (state != State.PLAYING) return

        if (positionStableStartTime == 0L) {
            positionStableStartTime = System.currentTimeMillis()
        }

        val stableDuration = System.currentTimeMillis() - positionStableStartTime

        if (stableDuration >= SILENCE_THRESHOLD_MS) {
            handleSilence()
        } else if (System.currentTimeMillis() - playbackStartTime >= MAX_PLAYBACK_DURATION_MS) {
            Log.w(TAG, "Max playback duration reached, forcing end")
            handleSilence()
        }
    }

    private val positionCheckRunnable = object : Runnable {
        override fun run() {
            if (this@AudioMonitorService.state == State.PLAYING) {
                // Check if position has been stable (no activity for a while)
                val timeSinceLastActivity = System.currentTimeMillis() - lastActivityTime

                if (timeSinceLastActivity >= SILENCE_THRESHOLD_MS) {
                    if (positionStableStartTime == 0L) {
                        positionStableStartTime = System.currentTimeMillis()
                    }
                    val stableDuration = System.currentTimeMillis() - positionStableStartTime
                    if (stableDuration >= SILENCE_THRESHOLD_MS) {
                        handleSilence()
                    }
                } else {
                    positionStableStartTime = 0L
                }

                // Max duration check
                if (System.currentTimeMillis() - playbackStartTime >= MAX_PLAYBACK_DURATION_MS) {
                    Log.w(TAG, "Max playback duration reached")
                    handleSilence()
                }
            }
            handler.postDelayed(this, POSITION_CHECK_INTERVAL_MS)
        }
    }

    private val pageCheckResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CHECK_PAGE_RESULT) {
                val allowsAudio = intent.getBooleanExtra(EXTRA_PAGE_ALLOWS_AUDIO, false)
                handlePageCheckResult(allowsAudio)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        try {
            audioManager?.registerAudioPlaybackCallback(playbackCallback, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register audio callback", e)
        }

        handler.post(positionCheckRunnable)
        ConfigManager.init(this)

        val filter = IntentFilter(ACTION_CHECK_PAGE_RESULT)
        LocalBroadcastManager.getInstance(this).registerReceiver(pageCheckResultReceiver, filter)

        Log.d(TAG, "AudioMonitorService started")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            audioManager?.unregisterAudioPlaybackCallback(playbackCallback)
        } catch (e: Exception) { }
        handler.removeCallbacksAndMessages(null)
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(pageCheckResultReceiver)
        } catch (e: Exception) { }
        ensureUnmuted()
        super.onDestroy()
    }

    private fun startPlayback() {
        state = State.PLAYING
        playbackStartTime = System.currentTimeMillis()
        lastActivityTime = System.currentTimeMillis()
        positionStableStartTime = 0L
        isPlaying = true
        playbackIdCounter++

        Log.d(TAG, "Playback started #$playbackIdCounter")
        ConfigManager.appendLog("AMS", ">>> START_PLAYBACK #$playbackIdCounter")

        audioTriggerTime = System.currentTimeMillis()

        // IMMEDIATELY mute — prevent audio leak during page scan
        ensureMuted()
        isMuted = true

        // Pull DiDi to foreground for page check
        pendingPageCheck = true
        pageCheckStartTime = System.currentTimeMillis()
        pullDidiToForeground()

        broadcastState()
    }

    private fun handleSilence() {
        if (state != State.PLAYING) return

        val endTime = System.currentTimeMillis()
        val duration = endTime - playbackStartTime

        state = State.IDLE
        // Don't set pendingPageCheck = false here — let page check result (or timeout) handle unmute
        isPlaying = false
        ConfigManager.appendLog("AMS", "<<< SILENCE duration=$duration ms isMuted=$isMuted pendingPageCheck=$pendingPageCheck")
        Log.d(TAG, "Playback ended, duration=$duration ms")

        // Record with current state; page check result will update allowReason
        val reason = if (isMuted) "无" else "待定"
        val record = PlaybackRecord(
            id = playbackIdCounter,
            startTime = playbackStartTime,
            endTime = endTime,
            duration = duration,
            wasMuted = isMuted,
            allowReason = reason
        )
        ConfigManager.addPlaybackRecord(record)

        // Don't unmute here — page check result (or timeout) will decide
        // If no page check was pending, unmute now
        if (!pendingPageCheck) {
            ensureUnmuted()
            isMuted = false
        }
        broadcastState()
    }

    private fun handlePageCheckResult(pageAllowsAudio: Boolean) {
        if (!pendingPageCheck) return

        pendingPageCheck = false
        val elapsed = if (audioTriggerTime > 0) System.currentTimeMillis() - audioTriggerTime else 0
        ConfigManager.appendLog("AMS", "=== PAGE_RESULT allowsAudio=$pageAllowsAudio elapsed=${elapsed}ms ===")
        audioTriggerTime = 0

        if (pageAllowsAudio) {
            ensureUnmuted()
            isMuted = false
            Log.d(TAG, "Page allows audio - unmuted")
        } else {
            ensureMuted()
            isMuted = true
            Log.d(TAG, "Page blocks audio - muted")
        }

        updateLastRecord(pageAllowsAudio)
        broadcastState()
    }

    private fun updateLastRecord(pageAllowsAudio: Boolean) {
        val records = ConfigManager.playbackRecords
        if (records.isNotEmpty()) {
            val last = records.last()
            if (last.id == playbackIdCounter) {
                val reason = if (pageAllowsAudio) {
                    val allowTexts = ConfigManager.allowTexts
                    when {
                        allowTexts.contains("预约单") || allowTexts.contains("专车舒适") || allowTexts.contains("不抢") -> "预约单"
                        allowTexts.contains("实时单") || allowTexts.contains("接乘客") -> "实时单"
                        else -> "通知同时"
                    }
                } else {
                    "无"
                }
                val updated = last.copy(wasMuted = isMuted, allowReason = reason)
                records.removeLast()
                records.add(updated)
                ConfigManager.save()
            }
        }
    }

    private fun pullDidiToForeground() {
        try {
            val pm = packageManager
            val intent = pm.getLaunchIntentForPackage("com.sdu.didi.gsui")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Log.d(TAG, "Pulled DiDi to foreground")
            }

            val checkIntent = Intent(this, SmartVoiceBlocker::class.java).apply {
                action = SmartVoiceBlocker.ACTION_REQUEST_PAGE_CHECK
            }
            startService(checkIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull DiDi to foreground", e)
        }
    }

    private fun ensureMuted() {
        if (!isMuted) {
            try {
                audioManager?.setStreamMute(AudioManager.STREAM_MUSIC, true)
                isMuted = true
                ConfigManager.appendLog("AMS", "### MUTED ###")
                Log.d(TAG, "MUTED")
            } catch (e: Exception) {
                ConfigManager.appendLog("AMS", "### MUTE_FAILED: ${e.message} ###")
                Log.e(TAG, "Mute failed", e)
            }
        }
    }

    private fun ensureUnmuted() {
        if (isMuted) {
            try {
                audioManager?.setStreamMute(AudioManager.STREAM_MUSIC, false)
                isMuted = false
                ConfigManager.appendLog("AMS", "### UNMUTED ###")
                Log.d(TAG, "UNMUTED")
            } catch (e: Exception) {
                ConfigManager.appendLog("AMS", "### UNMUTE_FAILED: ${e.message} ###")
                Log.e(TAG, "Unmute failed", e)
            }
        }
    }

    private fun broadcastState() {
        val intent = Intent(ACTION_PLAYBACK_STATE_CHANGED).apply {
            putExtra(EXTRA_IS_PLAYING, isPlaying)
            putExtra(EXTRA_IS_MUTED, isMuted)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音频监控",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, AudioMonitorService::class.java).apply {
            action = "STOP"
        }
        val stopPending = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("滴滴静音器")
            .setContentText("音频监控中")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭", stopPending)
            .setOngoing(true)
            .build()
    }
}
