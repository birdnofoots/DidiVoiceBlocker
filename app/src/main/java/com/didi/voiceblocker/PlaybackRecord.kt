package com.didi.voiceblocker

data class PlaybackRecord(
    val id: Int,
    val startTime: Long,
    val endTime: Long,
    val duration: Long,
    val wasMuted: Boolean,
    val allowReason: String
)
