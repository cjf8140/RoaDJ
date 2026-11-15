package com.tikitacar.roadj.media

import com.tikitacar.roadj.PlaybackController
import java.util.Locale

/** 미디어 화면과 MediaSession 이 공통으로 사용하는 표시 문자열 도우미 */
internal object MediaDisplay {

    /** 가수명 + 현재 주행 무드 태그 (예: "어반 자카파 • #야간주행") */
    fun artistWithMoodTag(baseArtist: String): String {
        val carState = PlaybackController.carState()
        val tags = mutableListOf<String>()
        tags += if (carState.time == "day") "#주간주행" else "#야간주행"
        if (carState.mood == "happy") tags += "#신나는주행"

        val artist = baseArtist.ifBlank { "RoaDJ" }
        return "$artist • ${tags.joinToString(" ")}"
    }

    /** 밀리초를 m:ss 형식으로 표시 */
    fun formatTime(millis: Long): String {
        val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
        return String.format(
            Locale.US,
            "%d:%02d",
            totalSeconds / 60L,
            totalSeconds % 60L
        )
    }

    const val DEFAULT_DURATION_MS = 240_000L
}
