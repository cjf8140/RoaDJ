package com.tikitacar.roadj

import org.junit.Assert.assertEquals
import org.junit.Test

/** RANKING mood 의 75km/h 경계 동작을 검증합니다. */
class RankingMoodTest {

    @Test
    fun zeroSpeedIsCalm() {
        assertEquals("calm", RankingMood.forSpeedKmh(0f))
    }

    @Test
    fun justBelowThresholdIsCalm() {
        assertEquals("calm", RankingMood.forSpeedKmh(74.9f))
    }

    @Test
    fun exactlyThresholdIsHappy() {
        assertEquals("happy", RankingMood.forSpeedKmh(75.0f))
    }

    @Test
    fun aboveThresholdIsHappy() {
        assertEquals("happy", RankingMood.forSpeedKmh(120f))
    }
}
