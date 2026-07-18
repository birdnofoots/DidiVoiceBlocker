package com.didi.voiceblocker

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

// 出车拍照状态(每天清零)
object DriverPhotoStore {
    private const val PREFS_NAME = "driver_photo"
    private var prefs: SharedPreferences? = null

    private const val KEY_PHOTO_COMPLETED = "photo_completed"
    private const val KEY_PHOTO_CHECKED_DAY = "photo_checked_day"

    var photoCompleted: Boolean = false
        private set
    // 今天已检查过,不再重复导航
    var photoCheckedToday: Boolean = false
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        val p = prefs ?: return
        val checkedDay = p.getInt(KEY_PHOTO_CHECKED_DAY, 0)
        val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)

        if (checkedDay == today) {
            // 同一天:恢复状态
            photoCheckedToday = true
            photoCompleted = p.getBoolean(KEY_PHOTO_COMPLETED, false)
        } else {
            // 新的一天:清空
            photoCheckedToday = false
            photoCompleted = false
            save()
        }
    }

    fun setPhotoCompleted(completed: Boolean) {
        photoCompleted = completed
        photoCheckedToday = true
        val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        prefs?.edit()?.apply {
            putBoolean(KEY_PHOTO_COMPLETED, completed)
            putInt(KEY_PHOTO_CHECKED_DAY, today)
            apply()
        }
    }

    private fun save() {
        prefs?.edit()?.putBoolean(KEY_PHOTO_COMPLETED, photoCompleted)?.apply()
    }
}
