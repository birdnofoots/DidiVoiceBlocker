package com.didi.voiceblocker

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DriverDataStore {
    private const val PREFS_NAME = "driver_monitor"
    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    private const val KEY_TODAY_ORDERS = "today_orders"
    private const val KEY_START_OF_DAY_TOTAL = "start_of_day_total"
    private const val KEY_HALF_MONTH_ORDERS = "half_month_orders"
    private const val KEY_MORNING_PEAK_TODAY = "morning_peak_today"
    private const val KEY_MORNING_PEAK_PREV = "morning_peak_prev"
    private const val KEY_EVENING_PEAK_TODAY = "evening_peak_today"
    private const val KEY_EVENING_PEAK_PREV = "evening_peak_prev"
    private const val KEY_NIGHT_PEAK_TODAY = "night_peak_today"
    private const val KEY_NIGHT_PEAK_PREV = "night_peak_prev"
    private const val KEY_WEEKEND_PEAK_TODAY = "weekend_peak_today"
    private const val KEY_WEEKEND_PEAK_PREV = "weekend_peak_prev"
    private const val KEY_MANUAL_PAUSED = "manual_paused"
    private const val KEY_TIMER_STOPPED = "timer_stopped"
    private const val KEY_LAST_RESET_DAY = "last_reset_day"
    private const val KEY_LAST_RESET_PERIOD = "last_reset_period"

    var todayOrders: Int = 0
        private set
    var startOfDayTotal: Int = 0
        private set
    var halfMonthOrders: Int = 0
        private set
    var morningPeakToday: Long = 0
        private set
    var morningPeakPrev: Long = 0
        private set
    var eveningPeakToday: Long = 0
        private set
    var eveningPeakPrev: Long = 0
        private set
    var nightPeakToday: Long = 0
        private set
    var nightPeakPrev: Long = 0
        private set
    var weekendPeakToday: Long = 0
        private set
    var weekendPeakPrev: Long = 0
        private set
    var manualPaused: Boolean = false
        private set
    var timerStopped: Boolean = false
        private set
    private var lastResetPeriod: Int = 0

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        val p = prefs ?: return
        todayOrders = p.getInt(KEY_TODAY_ORDERS, 0)
        startOfDayTotal = p.getInt(KEY_START_OF_DAY_TOTAL, 0)
        halfMonthOrders = p.getInt(KEY_HALF_MONTH_ORDERS, 0)
        morningPeakToday = p.getLong(KEY_MORNING_PEAK_TODAY, 0)
        morningPeakPrev = p.getLong(KEY_MORNING_PEAK_PREV, 0)
        eveningPeakToday = p.getLong(KEY_EVENING_PEAK_TODAY, 0)
        eveningPeakPrev = p.getLong(KEY_EVENING_PEAK_PREV, 0)
        nightPeakToday = p.getLong(KEY_NIGHT_PEAK_TODAY, 0)
        nightPeakPrev = p.getLong(KEY_NIGHT_PEAK_PREV, 0)
        weekendPeakToday = p.getLong(KEY_WEEKEND_PEAK_TODAY, 0)
        weekendPeakPrev = p.getLong(KEY_WEEKEND_PEAK_PREV, 0)
        manualPaused = p.getBoolean(KEY_MANUAL_PAUSED, false)
        timerStopped = p.getBoolean(KEY_TIMER_STOPPED, false)
        lastResetPeriod = p.getInt(KEY_LAST_RESET_PERIOD, 0)
        // 首次运行或 prefs 被重置后，把 lastResetPeriod 初始化为当前 period，
        // 避免误把 "0 != 当前period" 当作 period 变化而清零所有数据
        if (lastResetPeriod == 0) {
            val cal = Calendar.getInstance()
            val day = cal.get(Calendar.DAY_OF_MONTH)
            lastResetPeriod = if (day <= 15) 1 else 16
            p.edit().putInt(KEY_LAST_RESET_PERIOD, lastResetPeriod).apply()
        }
    }

    fun save() {
        prefs?.edit()?.apply {
            putInt(KEY_TODAY_ORDERS, todayOrders)
            putInt(KEY_START_OF_DAY_TOTAL, startOfDayTotal)
            putInt(KEY_HALF_MONTH_ORDERS, halfMonthOrders)
            putLong(KEY_MORNING_PEAK_TODAY, morningPeakToday)
            putLong(KEY_MORNING_PEAK_PREV, morningPeakPrev)
            putLong(KEY_EVENING_PEAK_TODAY, eveningPeakToday)
            putLong(KEY_EVENING_PEAK_PREV, eveningPeakPrev)
            putLong(KEY_NIGHT_PEAK_TODAY, nightPeakToday)
            putLong(KEY_NIGHT_PEAK_PREV, nightPeakPrev)
            putLong(KEY_WEEKEND_PEAK_TODAY, weekendPeakToday)
            putLong(KEY_WEEKEND_PEAK_PREV, weekendPeakPrev)
            putBoolean(KEY_MANUAL_PAUSED, manualPaused)
            putBoolean(KEY_TIMER_STOPPED, timerStopped)
            putInt(KEY_LAST_RESET_PERIOD, lastResetPeriod)
            apply()
        }
    }

    fun updateOrderCount(count: Int) {
        val delta = count - todayOrders
        if (delta > 0) {
            halfMonthOrders += delta  // JS 风格：只增不减，delta 为正才累加
        }
        todayOrders = count
        save()
    }

    fun setManualPaused(paused: Boolean) {
        manualPaused = paused
        save()
    }

    fun setTimerStopped(stopped: Boolean) {
        timerStopped = stopped
        save()
    }

    // 调试用：手动设置订单累计数（临时修复）
    fun setManualOrderCount(halfMonthOrders: Int, startOfDayTotal: Int) {
        android.util.Log.d("DSDBG", "setManualOrderCount called: halfMonthOrders=$halfMonthOrders, startOfDayTotal=$startOfDayTotal")
        this.halfMonthOrders = halfMonthOrders
        this.startOfDayTotal = startOfDayTotal
        this.todayOrders = 0
        save()
        android.util.Log.d("DSDBG", "after save: halfMonthOrders=${this.halfMonthOrders}, startOfDayTotal=${this.startOfDayTotal}")
    }

    fun addPeakMinutes(type: String, millis: Long) {
        when (type) {
            "morning" -> morningPeakToday += millis
            "evening" -> eveningPeakToday += millis
            "night" -> nightPeakToday += millis
            "weekend" -> weekendPeakToday += millis
        }
        save()
    }

    fun checkDayReset() {
        val cal = Calendar.getInstance()
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val currentPeriod = if (day <= 15) 1 else 16

        // 1. 半月 period 变化 → 触发月清零（1→16 或 16→1）
        if (lastResetPeriod != 0 && currentPeriod != lastResetPeriod) {
            checkMonthlyReset()
            lastResetPeriod = currentPeriod
            prefs?.edit()?.putInt(KEY_LAST_RESET_PERIOD, currentPeriod)?.apply()
        }

        // 2. 每天日期变化 → 触发日清零（只搬移时长，halfMonthOrders 累积不清零）
        val lastResetDay = prefs?.getInt(KEY_LAST_RESET_DAY, 0) ?: 0
        if (day != lastResetDay) {
            morningPeakPrev += morningPeakToday
            eveningPeakPrev += eveningPeakToday
            nightPeakPrev += nightPeakToday
            weekendPeakPrev += weekendPeakToday
            morningPeakToday = 0
            eveningPeakToday = 0
            nightPeakToday = 0
            weekendPeakToday = 0
            startOfDayTotal = halfMonthOrders  // 保存日初的累计订单数（用于显示公式）
            todayOrders = 0
            prefs?.edit()?.putInt(KEY_LAST_RESET_DAY, day)?.apply()
            save()
        }
    }

    private fun checkMonthlyReset() {
        val cal = Calendar.getInstance()
        val day = cal.get(Calendar.DAY_OF_MONTH)
        if (day == 1 || day == 16) {
            exportHalfMonthData()
            morningPeakPrev = 0
            eveningPeakPrev = 0
            nightPeakPrev = 0
            weekendPeakPrev = 0
            morningPeakToday = 0
            eveningPeakToday = 0
            nightPeakToday = 0
            weekendPeakToday = 0
            halfMonthOrders = 0
            todayOrders = 0
            startOfDayTotal = 0
            save()
        }
    }

    private fun exportHalfMonthData() {
        val ctx = appContext ?: return
        val sdf = SimpleDateFormat("yyyy-MM", Locale.US)
        val month = sdf.format(Date())
        val cal = Calendar.getInstance()
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val period = if (day == 1) "上半月" else "下半月"

        val totalHours = formatHours(morningPeakToday + morningPeakPrev +
            eveningPeakToday + eveningPeakPrev +
            nightPeakToday + nightPeakPrev +
            weekendPeakToday + weekendPeakPrev)

        val line = "$month $period: 订单${halfMonthOrders} " +
            "早${formatHours(morningPeakToday + morningPeakPrev)} " +
            "晚${formatHours(eveningPeakToday + eveningPeakPrev)} " +
            "夜${formatHours(nightPeakToday + nightPeakPrev)} " +
            "周末${formatHours(weekendPeakToday + weekendPeakPrev)} " +
            "总${totalHours}\n"

        try {
            val dir = ctx.getExternalFilesDir(null) ?: return
            val file = File(dir, "driver_stats.log")
            file.appendText(line)
        } catch (_: Exception) {}
    }

    fun formatHours(millis: Long): String {
        val hours = millis / 3600000.0
        return String.format(Locale.US, "%.1fh", hours)
    }

    fun getTotalPeakHours(): String {
        val total = morningPeakToday + morningPeakPrev +
            eveningPeakToday + eveningPeakPrev +
            nightPeakToday + nightPeakPrev +
            weekendPeakToday + weekendPeakPrev
        return formatHours(total)
    }

    fun getDisplayOrder(): String {
        val total = startOfDayTotal + todayOrders
        return "$total = $todayOrders + $startOfDayTotal"
    }

    fun getDisplayPeak(type: String): String {
        val (today, prev) = when (type) {
            "morning" -> morningPeakToday to morningPeakPrev
            "evening" -> eveningPeakToday to eveningPeakPrev
            "night" -> nightPeakToday to nightPeakPrev
            "weekend" -> weekendPeakToday to weekendPeakPrev
            else -> 0L to 0L
        }
        val total = today + prev
        return "${formatHours(total)} = ${formatHours(today)} + ${formatHours(prev)}"
    }
}
