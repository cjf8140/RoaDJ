package com.tikitacar.roadj

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log

/**
 * `Labeler/RANKING.ko.md` 의 규칙을 그대로 옮긴 곡 순위 계산기.
 *
 * 차량 상태(`CarState`)를 받아 `tags.db` 의 `songs` 테이블을 SQL 한 번으로
 * 정렬합니다. 점수 합산이 아니라 위 카테고리부터 차례로 비교하는
 * 사전식(lexicographic) 정렬이며, 마지막에 mood 로 bpm 방향을 정합니다.
 */
data class CarState(
    val lang: String,   // ko | en | ja
    val event: String,  // none | christmas
    val precip: String, // none | rain | snow
    val place: String,  // none | mountain | sea | river | city
    val time: String,   // day | night
    val season: String, // spring | summer | autumn | winter
    val mood: String    // happy | calm (bpm 정렬 방향)
)

data class RankedSong(
    val id: String,
    val file: String,
    val title: String?,
    val artist: String?,
    val bpm: Double?
)

/** 태그 표시/계산을 위한 곡 메타데이터 */
data class SongTagInfo(
    val file: String,
    val lang: String?,
    val event: String?,
    val precip: String?,
    val place: String?,
    val time: String?,
    val season: String?,
    val bpm: Double?
)

object SongRanker {

    private const val TAG = "RoadJRank"
    private const val DB_NAME = "tags.db"

    /** RANKING.ko.md 의 SQL 점수식. ? 는 9개(lang 1, event 2, precip 2, place 2, time 1, season 1) */
    private val SCORES = """
        CASE WHEN lang IS NULL THEN 0
             WHEN lang = ? THEN 1
             ELSE -1 END AS lang_score,
        CASE WHEN event IS NULL AND ? = 'none' THEN 1
             WHEN event IS NOT NULL AND (',' || event || ',') LIKE ('%,' || ? || ',%') THEN 1
             ELSE -1 END AS event_score,
        CASE WHEN precip IS NULL AND ? = 'none' THEN 1
             WHEN precip IS NOT NULL AND (',' || precip || ',') LIKE ('%,' || ? || ',%') THEN 1
             ELSE -1 END AS precip_score,
        CASE WHEN place IS NULL AND ? = 'none' THEN 1
             WHEN place IS NOT NULL AND (',' || place || ',') LIKE ('%,' || ? || ',%') THEN 1
             ELSE -1 END AS place_score,
        CASE WHEN time IS NULL THEN 0
             WHEN (',' || time || ',') LIKE ('%,' || ? || ',%') THEN 1
             ELSE -1 END AS time_score,
        CASE WHEN season IS NULL THEN 0
             WHEN (',' || season || ',') LIKE ('%,' || ? || ',%') THEN 1
             ELSE -1 END AS season_score
    """.trimIndent()

    @Volatile
    private var cachedDb: SQLiteDatabase? = null

    /**
     * assets/tags.db 를 앱 DB 경로로 복사한 뒤 정렬된 곡 목록을 반환합니다.
     * 프로세스당 한 번만 복사하므로 앱이 갱신되면 다음 실행에서 최신 DB 를 씁니다.
     */
    @Synchronized
    fun rank(context: Context, state: CarState): List<RankedSong> {
        val db = open(context)
        val dir = if (state.mood == "happy") "DESC" else "ASC"
        val sql = """
            SELECT id, file, title, artist, bpm,
                   $SCORES
            FROM songs
            ORDER BY lang_score   DESC,
                     event_score  DESC,
                     precip_score DESC,
                     place_score  DESC,
                     time_score   DESC,
                     season_score DESC,
                     bpm $dir,
                     title
        """.trimIndent()

        val args = arrayOf(
            state.lang,
            state.event, state.event,
            state.precip, state.precip,
            state.place, state.place,
            state.time, state.season
        )

        val songs = ArrayList<RankedSong>(32)
        try {
            db.rawQuery(sql, args).use { c ->
                while (c.moveToNext()) {
                    songs += RankedSong(
                        id = c.getString(0),
                        file = c.getString(1),
                        title = if (c.isNull(2)) null else c.getString(2),
                        artist = if (c.isNull(3)) null else c.getString(3),
                        bpm = if (c.isNull(4)) null else c.getDouble(4)
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "순위 쿼리 실패", e)
        }
        Log.d(TAG, "state=$state -> ${songs.size}곡")
        return songs
    }

    /**
     * 모든 곡의 태그/BPM 정보를 조회합니다. (화면 태그 표시용, 정적 데이터)
     */
    @Synchronized
    fun allTagInfo(context: Context): List<SongTagInfo> {
        val db = open(context)
        val result = ArrayList<SongTagInfo>(32)
        try {
            db.rawQuery(
                "SELECT file, lang, event, precip, place, time, season, bpm FROM songs",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    result += SongTagInfo(
                        file = c.getString(0),
                        lang = if (c.isNull(1)) null else c.getString(1),
                        event = if (c.isNull(2)) null else c.getString(2),
                        precip = if (c.isNull(3)) null else c.getString(3),
                        place = if (c.isNull(4)) null else c.getString(4),
                        time = if (c.isNull(5)) null else c.getString(5),
                        season = if (c.isNull(6)) null else c.getString(6),
                        bpm = if (c.isNull(7)) null else c.getDouble(7)
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "태그 정보 조회 실패", e)
        }
        return result
    }

    private fun open(context: Context): SQLiteDatabase {
        cachedDb?.let { if (it.isOpen) return it }

        val target = context.getDatabasePath(DB_NAME)
        target.parentFile?.mkdirs()
        try {
            context.assets.open(DB_NAME).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            // 복사가 실패해도 기존 DB 가 있으면 그대로 사용합니다.
            if (!target.exists()) throw e
            Log.w(TAG, "tags.db 복사 실패, 기존 파일 사용", e)
        }

        val db = SQLiteDatabase.openDatabase(
            target.path, null, SQLiteDatabase.OPEN_READONLY
        )
        cachedDb = db
        return db
    }

    /** 앱 종료 시 호출 (선택) */
    @Synchronized
    fun close() {
        cachedDb?.let { runCatching { it.close() } }
        cachedDb = null
    }

    /** 곡 파일명(확장자 포함)이 assets/audio 에 실제로 있는지 확인할 때 사용합니다. */
    fun assetTracks(context: Context): List<String> =
        context.assets.list("audio")
            ?.filter { it.endsWith(".mp3", ignoreCase = true) }
            ?.sorted()
            .orEmpty()
}
