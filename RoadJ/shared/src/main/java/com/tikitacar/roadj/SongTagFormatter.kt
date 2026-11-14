package com.tikitacar.roadj

import android.content.Context

/**
 * 곡별 태그 중 **현재 주행 상황과 일치하는 태그만** 화면 표시용으로 변환합니다.
 *
 * - 우선순위: event → precip → place → time → season
 * - 곡에 #바다 태그가 있어도 현재 위치가 바다가 아니면 표시하지 않습니다.
 * - 일치하는 태그가 3개 미만이면 전체 곡 BPM 상위 50% → #happy,
 *   하위 50% → #calm 을 보충합니다.
 * - 언어(lang) 태그는 화면에 표시하지 않습니다.
 */
object SongTagFormatter {

    private const val DEFAULT_MAX_TAGS = 3

    private val EVENT_LABELS = mapOf(
        "christmas" to "크리스마스"
    )
    private val PRECIP_LABELS = mapOf(
        "rain" to "비",
        "snow" to "눈"
    )
    private val PLACE_LABELS = mapOf(
        "mountain" to "산",
        "sea" to "바다",
        "river" to "강",
        "city" to "도시"
    )
    private val TIME_LABELS = mapOf(
        "day" to "낮",
        "night" to "밤"
    )
    private val SEASON_LABELS = mapOf(
        "spring" to "봄",
        "summer" to "여름",
        "autumn" to "가을",
        "winter" to "겨울"
    )

    private var cachedSongs: List<SongTagInfo>? = null
    private var cachedBpmThreshold: Double? = null
    private var bpmThresholdLoaded = false

    @Synchronized
    fun tagsFor(
        context: Context,
        file: String,
        carState: CarState,
        maxTags: Int = DEFAULT_MAX_TAGS
    ): List<String> {
        val songs = loadSongs(context)
        val song = songs.firstOrNull { it.file == file } ?: return emptyList()
        val tags = mutableListOf<String>()

        // 곡의 태그 값 중 현재 상황과 일치하는 것만 표시합니다.
        addMatchingLabel(tags, song.event, EVENT_LABELS, carState.event, maxTags)
        addMatchingLabel(tags, song.precip, PRECIP_LABELS, carState.precip, maxTags)
        addMatchingLabel(tags, song.place, PLACE_LABELS, carState.place, maxTags)
        addMatchingLabel(tags, song.time, TIME_LABELS, carState.time, maxTags)
        addMatchingLabel(tags, song.season, SEASON_LABELS, carState.season, maxTags)

        // 일치하는 태그가 3개 미만이면 BPM 기준 happy/calm 을 보충합니다.
        if (tags.size < maxTags) {
            val bpm = song.bpm
            val threshold = bpmThreshold(songs)
            if (bpm != null && threshold != null) {
                val bpmTag = if (bpm >= threshold) "happy" else "calm"
                if (bpmTag !in tags) tags += bpmTag
            }
        }

        return tags.take(maxTags)
    }

    private fun loadSongs(context: Context): List<SongTagInfo> {
        cachedSongs?.let { return it }
        val songs = SongRanker.allTagInfo(context)
        cachedSongs = songs
        return songs
    }

    /** 전체 곡 BPM 을 오름차순 정렬했을 때 상위 50%의 경계값 */
    private fun bpmThreshold(songs: List<SongTagInfo>): Double? {
        if (bpmThresholdLoaded) return cachedBpmThreshold
        val bpms = songs.mapNotNull { it.bpm }.sorted()
        val threshold = if (bpms.isEmpty()) null else bpms[bpms.size / 2]
        cachedBpmThreshold = threshold
        bpmThresholdLoaded = true
        return threshold
    }

    /**
     * 곡의 태그 값(콤마 구분) 중 현재 상태 값과 일치하는 라벨만 추가합니다.
     * 예: 곡 place="city,river" 이고 현재 place="city" 이면 #도시만 표시
     */
    private fun addMatchingLabel(
        out: MutableList<String>,
        raw: String?,
        labels: Map<String, String>,
        current: String,
        maxTags: Int
    ) {
        if (out.size >= maxTags) return
        if (current.isBlank() || current.equals("none", ignoreCase = true)) return
        if (raw.isNullOrBlank() || raw.equals("none", ignoreCase = true)) return

        val currentKey = current.trim().lowercase()
        val songValues = raw.split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        if (currentKey !in songValues) return

        val label = labels[currentKey] ?: currentKey
        if (label !in out) out += label
    }
}
