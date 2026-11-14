package com.tikitacar.roadj

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * 앱 프로세스 전체에서 하나만 유지되는 재생 컨트롤러.
 *
 * - [LocalAudioPlayer](로컬 mp3 재생)를 소유하고 RANKING 순위를 관리합니다.
 * - MainActivity 가 이 컨트롤러의 상태를 구독해 화면에 표시합니다.
 * - 액티비티가 destroy 되어도 플레이어를 해제하지 않아 재생이 계속됩니다.
 */
object PlaybackController {

    private const val TAG = "RoadJPlayback"
    private const val PROGRESS_TICK_MS = 1_000L

    /** 사용자가 선택한 곡 언어(ko/en/ja) 저장 키 */
    private const val PREFS_NAME = "roadj_settings"
    private const val KEY_RANKING_LANG = "ranking_lang"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(LocalAudioPlayer.State) -> Unit>()

    @Volatile
    private var initialized = false
    private var appContext: Context? = null
    private var player: LocalAudioPlayer? = null

    @Volatile
    private var carState = CarState(
        lang = "ko",
        event = "none",
        precip = "none",
        place = "none",
        time = "night",
        season = "autumn",
        mood = "calm"
    )

    @Volatile
    private var sunriseEpochSec = 0L

    @Volatile
    private var sunsetEpochSec = 0L

    /** 현재 위치의 시간대. 날씨 응답의 timezone 으로 갱신됩니다. */
    @Volatile
    private var locationTimeZone: TimeZone = TimeZone.getDefault()

    private var playlistGeneration = 0

    private val progressTicker = object : Runnable {
        override fun run() {
            // 시계/달력이 바뀌면(낮↔밤, 계절, 크리스마스) 순위도 자동 갱신합니다.
            refreshClockState()
            mainHandler.postDelayed(this, PROGRESS_TICK_MS)
        }
    }

    /**
     * 앱 어디서든 호출해도 안전하게 한 번만 초기화합니다.
     */
    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            appContext = app

            carState = carState.copy(
                lang = savedLanguage(app),
                event = currentEventCode(),
                time = currentTimeOfDay(),
                season = currentSeasonCode()
            )

            player = LocalAudioPlayer(app) { state ->
                listeners.forEach { runCatching { it(state) } }
            }

            initialized = true
            refreshPlaylist("초기화")
            mainHandler.removeCallbacks(progressTicker)
            mainHandler.post(progressTicker)
        }
    }

    /** UI 에 재생 상태를 전달받을 리스너 등록. 해제 함수를 반환합니다. */
    fun addListener(listener: (LocalAudioPlayer.State) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun state(): LocalAudioPlayer.State =
        player?.state() ?: LocalAudioPlayer.State(
            title = "재생할 곡 없음",
            artist = "RoadJ",
            songTitle = "재생할 곡 없음",
            position = 0,
            total = 0,
            playing = false,
            positionMs = 0L,
            durationMs = 0L
        )

    fun toggle() {
        if (state().playing) pause() else play()
    }

    fun play() {
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    fun next() {
        player?.next()
    }

    fun previous() {
        player?.previous()
    }

    /** 재생 위치 이동 (밀리초). MediaSession/시크바에서 호출. */
    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    fun carState(): CarState = carState

    /** 날씨/주변/시계 등 상태가 실제로 바뀐 경우에만 순위를 다시 계산합니다. */
    fun updateCarState(transform: (CarState) -> CarState) {
        val updated = transform(carState)
        if (updated == carState) return
        carState = updated
        refreshPlaylist("상태 변경")
    }

    /** 일출/일몰 epoch(초)를 갱신하고 낮/밤을 다시 계산합니다. */
    fun updateSunTimes(sunrise: Long, sunset: Long) {
        sunriseEpochSec = sunrise
        sunsetEpochSec = sunset
        updateCarState { it.copy(time = currentTimeOfDay()) }
    }

    /** 현재 위치의 시간대를 설정하고 날짜/계절/이벤트를 다시 계산합니다. */
    fun setLocationTimeZone(timeZone: TimeZone) {
        locationTimeZone = timeZone
        refreshClockState()
    }

    /** 시계/달력 기준 상태(event/time/season)를 갱신합니다. MainActivity 시계 tick 에서 호출. */
    fun refreshClockState() {
        updateCarState {
            it.copy(
                event = currentEventCode(),
                time = currentTimeOfDay(),
                season = currentSeasonCode()
            )
        }
    }

    /**
     * 사용자가 선택한 곡 언어를 적용하고 저장합니다.
     * 다음 실행에서도 유지되며, 순위 기준 `lang` 값으로 사용됩니다.
     */
    fun setLanguage(lang: String) {
        val normalized = normalizeLanguage(lang)
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_RANKING_LANG, normalized)
            ?.apply()
        updateCarState { it.copy(lang = normalized) }
    }

    /**
     * 실시간 차량 속도를 반영합니다.
     * 75km/h 이상이면 mood=happy(bpm 내림차순), 미만이면 mood=calm(bpm 오름차순).
     */
    fun updateSpeed(speedKmh: Float) {
        updateCarState { it.copy(mood = RankingMood.forSpeedKmh(speedKmh)) }
    }

    /** RANKING 의 lang 값. ko/en/ja 가 아니면 ko. */
    private fun normalizeLanguage(lang: String): String =
        if (lang == "ko" || lang == "en" || lang == "ja") lang else "ko"

    /** 저장된 사용자 선택이 있으면 그 값을, 없으면 시스템 언어를 사용합니다. */
    private fun savedLanguage(context: Context): String {
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RANKING_LANG, null)
        return normalizeLanguage(saved ?: preferredLanguage())
    }

    /** RANKING 의 lang 기본값. ko/en/ja 가 아니면 ko. */
    private fun preferredLanguage(): String {
        val code = Locale.getDefault().language.lowercase(Locale.US)
        return normalizeLanguage(code)
    }

    private fun currentEventCode(): String {
        val now = Calendar.getInstance(locationTimeZone)
        return if (now.get(Calendar.MONTH) + 1 == 12 && now.get(Calendar.DAY_OF_MONTH) == 25) {
            "christmas"
        } else {
            "none"
        }
    }

    private fun currentSeasonCode(): String =
        when (Calendar.getInstance(locationTimeZone).get(Calendar.MONTH) + 1) {
            3, 4, 5 -> "spring"
            6, 7, 8 -> "summer"
            9, 10, 11 -> "autumn"
            else -> "winter"
        }

    /** 일출~일몰이면 day, 아니면 night. 일출/일몰을 모르면 06~17시를 낮으로 봅니다. */
    private fun currentTimeOfDay(): String {
        val nowSec = System.currentTimeMillis() / 1000L
        val sunrise = sunriseEpochSec
        val sunset = sunsetEpochSec
        if (sunrise > 0L && sunset > sunrise) {
            return if (nowSec in sunrise until sunset) "day" else "night"
        }
        val hour = Calendar.getInstance(locationTimeZone).get(Calendar.HOUR_OF_DAY)
        return if (hour in 6..17) "day" else "night"
    }

    /**
     * CarState 로 tags.db 를 정렬해 플레이어의 재생 순서를 교체합니다.
     * DB 조회는 백그라운드에서 하고, 결과는 최신 세대일 때만 반영합니다.
     */
    private fun refreshPlaylist(reason: String) {
        val localPlayer = player ?: return
        val context = appContext ?: return
        val state = carState
        val generation = ++playlistGeneration

        thread {
            val ranked = try {
                SongRanker.rank(context, state)
            } catch (e: Exception) {
                Log.w(TAG, "곡 순위 계산 실패", e)
                return@thread
            }

            val assetFiles = SongRanker.assetTracks(context).toSet()
            val rankedFiles = ranked.mapNotNull { song ->
                song.file.takeIf { it in assetFiles }
            }
            // tags.db 에 없는 mp3 는 뒤에 붙여서 26곡이 모두 재생되게 합니다.
            val playlist = rankedFiles + assetFiles.filter { it !in rankedFiles }.sorted()
            val unmatchedDb = ranked.size - rankedFiles.size

            mainHandler.post {
                if (generation != playlistGeneration) {
                    Log.d(TAG, "오래된 순위 결과 무시 ($reason)")
                    return@post
                }
                localPlayer.setPlaylist(playlist, reason)

                val head = playlist.take(5).mapIndexed { index, file ->
                    "${index + 1}. ${file.substringBeforeLast('.')}"
                }
                Log.d(
                    TAG,
                    "재생 순위 적용($reason): ${playlist.size}곡 · lang=${state.lang} " +
                        "event=${state.event} precip=${state.precip} place=${state.place} " +
                        "time=${state.time} season=${state.season} mood=${state.mood} · " +
                        head.joinToString(" | ") +
                        if (unmatchedDb > 0) " · DB미매칭=$unmatchedDb" else ""
                )
            }
        }
    }
}
