package com.didi.voiceblocker

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ConfigManager {
    private const val PREFS_NAME = "blocker_config"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ALLOW_TEXTS = "allow_texts"
    const val KEY_ALLOW_RESOURCE_IDS = "allow_resource_ids"
    const val KEY_BLOCK_HINTS = "block_hints"
    private const val KEY_MUTE_STATS = "mute_stats"
    private const val KEY_PLAYBACK_RECORDS = "playback_records"

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    var enabled: Boolean = true
    var allowTexts: MutableSet<String> = mutableSetOf()
    var allowResourceIds: MutableSet<String> = mutableSetOf()
    var blockHints: MutableSet<String> = mutableSetOf()
    var playbackRecords: MutableList<PlaybackRecord> = mutableListOf()

    var silenceThresholdMs: Long = 3000L
    var positionCheckIntervalMs: Long = 500L
    var maxPlaybackDurationMs: Long = 30000L
    var scanTimeoutMs: Long = 5000L
    var debounceMs: Long = 200L
    var pageScanDelayMs: Long = 250L

    const val ACTION_AUDIO_PARAMS_UPDATED = "com.didi.voiceblocker.AUDIO_PARAMS_UPDATED"

    private val defaultAllowTexts = setOf(
        // 预约单 keywords
        "预约单", "专车舒适", "不抢",
        // 实时单 keywords（接乘客阶段）
        "实时单", "接乘客"
    )

    private val defaultAllowResourceIds = setOf(
        "as_widget_realtime_order_title_text",
        "layout_booking_order_list_item",
        "btn_book_list_tel",
        "as_icon_booking",
        "activity_order_confirm",
        "activity_order_review",
        "fragment_order_review",
        "fragment_order_qr",
        "order_review_head_bar_view",
        "order_review_head_tag_start",
        "order_review_head_tag_end",
        "v_order_review_head_bar",
        "activity_order_trip_end",
        "travel_detail_order_info_address_from_txt",
        "travel_detail_order_info_address_to_txt",
        "travel_detail_order_info_passenger_count",
        "travel_detail_order_info_im_layout",
        "travel_detail_order_info_phone_btn",
        "layout_selfdriving_bottom_order_detail_view"
    )

    private val defaultBlockHints = setOf(
        "SafetyReminder", "FatigueAlert", "DrivingTime",
        "RestReminder", "SafetyDialog", "安全提醒",
        "疲劳驾驶", "请注意休息", "注意安全", "休息提醒"
    )

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        reload()
    }

    fun reload() {
        val p = prefs ?: return
        enabled = p.getBoolean(KEY_ENABLED, true)
        allowTexts = p.getStringSet(KEY_ALLOW_TEXTS, null)?.toMutableSet()
            ?: defaultAllowTexts.toMutableSet()
        allowResourceIds = p.getStringSet(KEY_ALLOW_RESOURCE_IDS, null)?.toMutableSet()
            ?: defaultAllowResourceIds.toMutableSet()
        blockHints = p.getStringSet(KEY_BLOCK_HINTS, null)?.toMutableSet()
            ?: defaultBlockHints.toMutableSet()

        // Load playback records
        playbackRecords.clear()
        val recordsJson = p.getString(KEY_PLAYBACK_RECORDS, "[]") ?: "[]"
        try {
            val jsonArray = JSONArray(recordsJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                playbackRecords.add(PlaybackRecord(
                    id = obj.optInt("id", 0),
                    startTime = obj.optLong("startTime", 0L),
                    endTime = obj.optLong("endTime", 0L),
                    duration = obj.optLong("duration", 0L),
                    wasMuted = obj.optBoolean("wasMuted", false),
                    allowReason = obj.optString("allowReason", "无")
                ))
            }
        } catch (_: Exception) { }

        silenceThresholdMs = p.getLong("silenceThresholdMs", 3000L)
        positionCheckIntervalMs = p.getLong("positionCheckIntervalMs", 500L)
        maxPlaybackDurationMs = p.getLong("maxPlaybackDurationMs", 30000L)
        scanTimeoutMs = p.getLong("scanTimeoutMs", 5000L)
        debounceMs = p.getLong("debounceMs", 200L)
        pageScanDelayMs = p.getLong("pageScanDelayMs", 250L)
    }

    fun save() {
        prefs?.edit()?.apply {
            putBoolean(KEY_ENABLED, enabled)
            putStringSet(KEY_ALLOW_TEXTS, allowTexts)
            putStringSet(KEY_ALLOW_RESOURCE_IDS, allowResourceIds)
            putStringSet(KEY_BLOCK_HINTS, blockHints)

            // Save playback records
            val jsonArray = JSONArray()
            for (r in playbackRecords) {
                val obj = JSONObject().apply {
                    put("id", r.id)
                    put("startTime", r.startTime)
                    put("endTime", r.endTime)
                    put("duration", r.duration)
                    put("wasMuted", r.wasMuted)
                    put("allowReason", r.allowReason)
                }
                jsonArray.put(obj)
            }
            putString(KEY_PLAYBACK_RECORDS, jsonArray.toString())

            apply()
        }
    }

    fun saveAudioParams(
        silenceThreshold: Long,
        positionCheckInterval: Long,
        maxPlaybackDuration: Long,
        scanTimeout: Long,
        debounce: Long,
        pageScanDelay: Long
    ) {
        silenceThresholdMs = silenceThreshold
        positionCheckIntervalMs = positionCheckInterval
        maxPlaybackDurationMs = maxPlaybackDuration
        scanTimeoutMs = scanTimeout
        debounceMs = debounce
        pageScanDelayMs = pageScanDelay
        prefs?.edit()?.apply {
            putLong("silenceThresholdMs", silenceThreshold)
            putLong("positionCheckIntervalMs", positionCheckInterval)
            putLong("maxPlaybackDurationMs", maxPlaybackDuration)
            putLong("scanTimeoutMs", scanTimeout)
            putLong("debounceMs", debounce)
            putLong("pageScanDelayMs", pageScanDelay)
            apply()
        }
        appContext?.let {
            LocalBroadcastManager.getInstance(it).sendBroadcast(Intent(ACTION_AUDIO_PARAMS_UPDATED))
        }
    }

    fun addText(text: String) {
        allowTexts.add(text)
        save()
    }

    fun removeText(text: String) {
        allowTexts.remove(text)
        save()
    }

    fun addResourceId(id: String) {
        allowResourceIds.add(id)
        save()
    }

    fun removeResourceId(id: String) {
        allowResourceIds.remove(id)
        save()
    }

    fun addBlockHint(hint: String) {
        blockHints.add(hint)
        save()
    }

    fun removeBlockHint(hint: String) {
        blockHints.remove(hint)
        save()
    }

    fun recordMute() {
        recordEvent("mute")
    }

    fun recordUnmute() {
        recordEvent("unmute")
    }

    private fun recordEvent(type: String) {
        val p = prefs ?: return
        val json = p.getString(KEY_MUTE_STATS, "{}") ?: "{}"
        val obj = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
        val now = System.currentTimeMillis()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))

        if (!obj.has("events")) obj.put("events", JSONArray())
        val events = obj.getJSONArray("events")
        val event = JSONObject().apply {
            put("time", now)
            put("type", type)
        }
        events.put(event)

        while (events.length() > 100) events.remove(0)

        var muteCount = obj.optInt("mute_count", 0)
        var unmuteCount = obj.optInt("unmute_count", 0)
        if (type == "mute") muteCount++ else unmuteCount++
        obj.put("mute_count", muteCount)
        obj.put("unmute_count", unmuteCount)
        obj.put("total", muteCount + unmuteCount)
        obj.put("last_date", today)

        p.edit().putString(KEY_MUTE_STATS, obj.toString()).apply()
    }

    fun getStats(): JSONObject {
        val json = prefs?.getString(KEY_MUTE_STATS, "{}") ?: "{}"
        return try { JSONObject(json) } catch (_: Exception) { JSONObject() }
    }

    fun clearStats() {
        prefs?.edit()?.putString(KEY_MUTE_STATS, "{}")?.apply()
    }

    fun resetToDefaults() {
        allowTexts = defaultAllowTexts.toMutableSet()
        allowResourceIds = defaultAllowResourceIds.toMutableSet()
        blockHints = defaultBlockHints.toMutableSet()
        save()
    }

    fun addPlaybackRecord(record: PlaybackRecord) {
        playbackRecords.add(record)
        if (playbackRecords.size > 2000) {
            playbackRecords = playbackRecords.takeLast(2000).toMutableList()
        }
        save()
    }

    fun getPlaybackRecordsList(): List<PlaybackRecord> = playbackRecords.toList()

    fun clearPlaybackRecords() {
        playbackRecords.clear()
        save()
    }

    // ── Debug log ───────────────────────────────────────────────
    private const val LOG_FILE = "debug.log"

    fun appendLog(tag: String, msg: String) {
        val ctx = appContext ?: return
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "[$ts][$tag] $msg\n"
        try {
            val file = java.io.File(ctx.getExternalFilesDir(null), LOG_FILE)
            file.appendText(line)
            // Trim to last 2000 lines if file gets too large
            val lines = file.readLines()
            if (lines.size > 2000) {
                file.writeText(lines.takeLast(2000).joinToString("\n") + "\n")
            }
        } catch (_: Exception) {}
    }

    fun getLogPath(): String {
        val ctx = appContext ?: return "(unknown)"
        return java.io.File(ctx.getExternalFilesDir(null), LOG_FILE).absolutePath
    }

    fun clearLog() {
        val ctx = appContext ?: return
        try {
            java.io.File(ctx.getExternalFilesDir(null), LOG_FILE).delete()
        } catch (_: Exception) {}
    }

    fun readLog(): String {
        val ctx = appContext ?: return ""
        val file = java.io.File(ctx.getExternalFilesDir(null), LOG_FILE)
        if (!file.exists()) return ""
        return try {
            val allLines = file.readLines()
            val last100 = if (allLines.size > 100) allLines.takeLast(100).reversed() else allLines.reversed()
            "(共 ${allLines.size} 条，仅显示最新 100 条)\n---\n" + last100.joinToString("\n")
        } catch (_: Exception) { "" }
    }

    // ── Wildcard matching ────────────────────────────────────────
    // Supports * (any chars) and ? (any single char)
    // Falls back to plain contains() if pattern has no wildcards
    fun matchesWildcard(text: String, pattern: String): Boolean {
        if (pattern.isEmpty()) return text.isEmpty()
        if (!pattern.contains('*') && !pattern.contains('?')) {
            // No wildcards — use fast plain contains
            return text.contains(pattern, ignoreCase = true)
        }
        val regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
        return try {
            Regex("^$regex$", RegexOption.IGNORE_CASE).containsMatchIn(text)
        } catch (_: Exception) {
            text.contains(pattern, ignoreCase = true)
        }
    }
}
