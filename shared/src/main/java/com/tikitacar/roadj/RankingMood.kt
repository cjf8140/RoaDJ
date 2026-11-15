package com.tikitacar.roadj

/**
 * RANKING 의 mood 값 계산 규칙.
 *
 * - 실시간 속도 75km/h 이상 → happy (bpm 내림차순)
 * - 75km/h 미만 → calm (bpm 오름차순)
 */
object RankingMood {

    const val HAPPY_SPEED_KMH = 75.0f

    fun forSpeedKmh(speedKmh: Float): String =
        if (speedKmh >= HAPPY_SPEED_KMH) "happy" else "calm"
}
