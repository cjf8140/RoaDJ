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

    /** 곡이 끝나기 몇 ms 전에 다음 곡을 미리 선택할지 */
    private const val NEXT_PREPARE_BEFORE_END_MS = 3_000L

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

    /** 한 번이라도 실제 재생이 시작된 곡 (다음 곡 선택에서 제외) */
    private val playedFiles = LinkedHashSet<String>()

    /** 현재 곡의 종료 3초 전에 다음 순서를 갱신했는지 여부 */
    private var nextOrderPrepared = false

    private val progressTicker = object : Runnable {
        override fun run() {
            // 시계/달력이 바뀌면(낮↔밤, 계절, 크리스마스) 순위도 자동 갱신합니다.
            refreshClockState()
            // 곡 종료 3초 전이면 최신 상태로 다음 곡을 미리 선택합니다.
            maybePrepareNextTrack()
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

            player = LocalAudioPlayer(
                app,
                onStateChanged = { state ->
                    listeners.forEach { runCatching { it(state) } }
                },
                onTrackStarted = { fileName -> onTrackStarted(fileName) },
                onTrackCompleted = { onTrackCompleted() }
            )

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

    /**
     * 다음 곡을 선택합니다. (수동 다음 곡)
     * 선택 시점의 CarState 로 RANKING 을 다시 계산하고, 아직 재생하지 않은 곡 중
     * 가장 우선순위가 높은 곡을 재생합니다.
     */
    fun next() {
        nextOrderPrepared = false
        val context = appContext
        val localPlayer = player
        if (context == null || localPlayer == null) return

        val playlist = buildPlaylist(context, carState)
        if (playlist.isEmpty()) {
            localPlayer.next()
            return
        }

        val currentFile = localPlayer.currentFileName()
        val candidate = playlist.firstOrNull { it !in playedFiles && it != currentFile }
        if (candidate == null) {
            Log.d(
                TAG,
                "다음 곡 없음 → 모든 곡 재생 완료 (${playedFiles.size}/${playlist.size})"
            )
            localPlayer.pause()
            return
        }

        localPlayer.setPlaylist(playlist, "다음 곡 선택")
        Log.d(
            TAG,
            "다음 곡 선택(수동): ${candidate.substringBeforeLast('.')} · " +
                "${carStateLog(carState)} · 재생한 곡 ${playedFiles.size}/${playlist.size}"
        )
        localPlayer.playFile(candidate)
    }

    fun previous() {
        nextOrderPrepared = false
        player?.previous()
    }

    /** 재생 목록/다음 곡 목록에서 특정 곡을 선택해 바로 재생합니다. */
    fun playSelected(fileName: String) {
        if (fileName.isBlank()) return
        nextOrderPrepared = false
        player?.playFile(fileName)
    }

    /** 재생 위치 이동 (밀리초). MediaSession/시크바에서 호출. */
    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    fun carState(): CarState = carState

    /** 현재 재생 중인 곡의 파일명 (태그 표시용) */
    fun currentFile(): String? = player?.currentFileName()

    /** 지금까지 실제로 재생을 시작한 곡 수 */
    fun playedCount(): Int = playedFiles.size

    /**
     * 현재 순위 순서에서 아직 재생하지 않은 곡을 앞에서부터 반환합니다.
     * (다음 곡 미리보기/재생 목록 UI 용)
     */
    fun upcomingFiles(limit: Int): List<String> {
        val localPlayer = player ?: return emptyList()
        val currentFile = localPlayer.currentFileName()
        return localPlayer.playlist()
            .asSequence()
            .filter { it != currentFile && it !in playedFiles }
            .take(limit)
            .toList()
    }

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

    /** 실제 재생이 시작된 곡을 기록합니다. (다음 곡 선택에서 제외) */
    private fun onTrackStarted(fileName: String) {
        val added = playedFiles.add(fileName)
        nextOrderPrepared = false
        if (added) {
            Log.d(
                TAG,
                "재생 이력 추가: ${fileName.substringBeforeLast('.')} (${playedFiles.size}곡 재생)"
            )
        }
    }

    /**
     * 곡이 끝났을 때, 3초 전에 갱신해 둔 순서에서 아직 재생하지 않은 첫 곡을 재생합니다.
     * 3초 전 갱신이 없었다면 종료 시점에 최신 상태로 순서를 갱신합니다.
     */
    private fun onTrackCompleted() {
        val localPlayer = player ?: return
        val hadPreparedOrder = nextOrderPrepared
        nextOrderPrepared = false

        if (hadPreparedOrder && localPlayer.playNextUnplayed(playedFiles)) {
            Log.d(
                TAG,
                "곡 종료 → 3초 전에 갱신한 순서로 다음 곡 재생: " +
                    (localPlayer.currentFileName()?.substringBeforeLast('.') ?: "-")
            )
            return
        }

        // 3초 전 갱신이 없었거나, 순서상 남은 미재생 곡이 없으면 종료 시점에 최신 상태로 갱신합니다.
        val context = appContext
        if (context == null) {
            localPlayer.pause()
            return
        }
        val playlist = buildPlaylist(context, carState)
        if (playlist.isEmpty()) {
            localPlayer.pause()
            return
        }
        localPlayer.setPlaylist(playlist, "곡 종료")
        if (localPlayer.playNextUnplayed(playedFiles)) {
            Log.d(
                TAG,
                "곡 종료 → 최신 순서로 다음 곡 재생: " +
                    (localPlayer.currentFileName()?.substringBeforeLast('.') ?: "-") +
                    " · ${carStateLog(carState)} · 재생한 곡 ${playedFiles.size}/${playlist.size}"
            )
        } else {
            Log.d(
                TAG,
                "곡 종료 → 재생할 새 곡 없음 (모든 곡 재생 완료 ${playedFiles.size}/${playlist.size})"
            )
            localPlayer.pause()
        }
    }

    /**
     * 곡 종료 3초 전이면 최신 환경 기준으로 다음 순서를 다시 설정합니다.
     * 여기서는 순서만 갱신하며, 곡을 미리 재생하거나 현재 곡을 바꾸지 않습니다.
     */
    private fun maybePrepareNextTrack() {
        val localPlayer = player ?: return
        val state = localPlayer.state()
        if (state.durationMs <= 0L) return

        val remaining = state.durationMs - state.positionMs

        // 시크 등으로 남은 시간이 다시 3초보다 커지면 다음 종료 시점에 다시 갱신하도록 초기화합니다.
        if (nextOrderPrepared && remaining > NEXT_PREPARE_BEFORE_END_MS) {
            nextOrderPrepared = false
        }

        if (state.playing &&
            remaining in 1L..NEXT_PREPARE_BEFORE_END_MS &&
            !nextOrderPrepared
        ) {
            refreshNextOrder("곡 종료 3초 전")
        }
    }

    /**
     * 현재 CarState 로 RANKING 순서를 다시 계산해 재생 목록에 반영합니다.
     * 현재 곡은 그대로 재생되며, 다음 곡을 미리 재생하지는 않습니다.
     */
    private fun refreshNextOrder(reason: String) {
        if (nextOrderPrepared) return
        val context = appContext ?: return
        val localPlayer = player ?: return

        val state = carState
        val playlist = buildPlaylist(context, state)
        if (playlist.isEmpty()) return

        // 현재 곡은 유지한 채 순서만 갱신합니다.
        localPlayer.setPlaylist(playlist, reason)
        nextOrderPrepared = true

        val head = playlist.take(5).mapIndexed { index, file ->
            "${index + 1}. ${file.substringBeforeLast('.')}"
        }
        Log.d(
            TAG,
            "다음 순서 갱신(3초 전): ${playlist.size}곡 · ${carStateLog(state)} · " +
                "재생한 곡 ${playedFiles.size}/${playlist.size} · " +
                head.joinToString(" | ")
        )
    }

    /** RANKING 결과와 DB 미매칭 곡을 합친 전체 재생 목록을 만듭니다. */
    private fun buildPlaylist(context: Context, state: CarState): List<String> {
        val assetFiles = SongRanker.assetTracks(context).toSet()
        val ranked = try {
            SongRanker.rank(context, state)
        } catch (e: Exception) {
            Log.w(TAG, "곡 순위 계산 실패", e)
            emptyList()
        }
        val rankedFiles = ranked.mapNotNull { song ->
            song.file.takeIf { it in assetFiles }
        }
        // tags.db 에 없는 mp3 는 뒤에 붙여서 모든 곡이 재생되게 합니다.
        return rankedFiles + assetFiles.filter { it !in rankedFiles }.sorted()
    }

    private fun carStateLog(state: CarState): String =
        "lang=${state.lang} event=${state.event} precip=${state.precip} " +
            "place=${state.place} time=${state.time} season=${state.season} mood=${state.mood}"

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
            val playlist = buildPlaylist(context, state)
            if (playlist.isEmpty()) return@thread

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
                    "재생 순위 적용($reason): ${playlist.size}곡 · ${carStateLog(state)} · " +
                        head.joinToString(" | ")
                )
            }
        }
    }
}
