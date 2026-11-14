package com.tikitacar.roadj

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import java.io.File

/**
 * APK `assets/audio` 에 포함된 mp3 를 기기 로컬에서 재생하는 플레이어.
 *
 * - 네트워크/저장소 권한 없이 동작합니다.
 * - 곡 순서는 [setPlaylist] 로 주입합니다. (RANKING.ko.md 기준 SongRanker 결과)
 * - 오디오 포커스(다른 앱/내비 안내음)를 요청하고, 포커스를 잃으면 일시정지합니다.
 */
class LocalAudioPlayer(
    context: Context,
    private val onStateChanged: (State) -> Unit
) {

    data class State(
        /** UI 표시용 전체 이름 (파일명에서 확장자 제외) */
        val title: String,
        /** 파일명 '가수 - 제목' 에서 가수 */
        val artist: String,
        /** 파일명 '가수 - 제목' 에서 제목 */
        val songTitle: String,
        val position: Int,
        val total: Int,
        val playing: Boolean,
        val positionMs: Long,
        val durationMs: Long
    )

    companion object {
        private const val TAG = "RoadJAudio"
        private const val AUDIO_DIR = "audio"
        private const val AUDIO_EXTENSION = ".mp3"
    }

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private var tracks: List<String> = appContext.assets
        .list(AUDIO_DIR)
        ?.filter { it.endsWith(AUDIO_EXTENSION, ignoreCase = true) }
        ?.sorted()
        .orEmpty()

    private var player: MediaPlayer? = null
    private var currentIndex = if (tracks.isEmpty()) -1 else 0
    private var playing = false

    private var focusRequest: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                player?.let { runCatching { it.setVolume(0.3f, 0.3f) } }
            AudioManager.AUDIOFOCUS_GAIN ->
                player?.let { runCatching { it.setVolume(1f, 1f) } }
        }
    }

    fun state(): State {
        val (artist, songTitle) = currentArtistAndTitle()
        return State(
            title = currentTitle(),
            artist = artist,
            songTitle = songTitle,
            position = if (currentIndex >= 0) currentIndex + 1 else 0,
            total = tracks.size,
            playing = playing,
            positionMs = currentPositionMs(),
            durationMs = currentDurationMs()
        )
    }

    /**
     * RANKING.ko.md 로 계산한 순서를 재생 목록에 반영합니다.
     * 재생 중인 곡은 끊지 않고, 그 곡의 새 위치에서 다음 곡을 이어갑니다.
     */
    fun setPlaylist(files: List<String>, reason: String) {
        val ordered = files
            .filter { it.endsWith(AUDIO_EXTENSION, ignoreCase = true) }
            .distinct()
        if (ordered.isEmpty()) {
            Log.w(TAG, "빈 재생 목록 무시 ($reason)")
            return
        }

        val current = if (player != null && currentIndex in tracks.indices) {
            tracks[currentIndex]
        } else {
            null
        }
        tracks = ordered
        currentIndex = when {
            current == null -> 0
            else -> ordered.indexOf(current).let { if (it >= 0) it else 0 }
        }

        Log.d(TAG, "재생 목록 갱신($reason): ${ordered.size}곡, 현재=${current ?: "-"} (${currentIndex + 1}번)")
        Log.d(TAG, "재생 순서($reason): " + ordered.joinToString(" | "))
        onStateChanged(state())
    }

    fun toggle() {
        if (playing) pause() else play()
    }

    fun play() {
        if (tracks.isEmpty()) {
            Log.w(TAG, "assets/$AUDIO_DIR 에 mp3 파일이 없습니다.")
            onStateChanged(state())
            return
        }
        val active = player
        if (active == null) {
            startTrack(if (currentIndex < 0) 0 else currentIndex)
            return
        }
        if (!requestFocus()) {
            onStateChanged(state())
            return
        }
        try {
            active.start()
            playing = true
        } catch (e: Exception) {
            Log.w(TAG, "재생 실패", e)
            playing = false
        }
        onStateChanged(state())
    }

    fun pause() {
        val active = player ?: return
        try {
            if (active.isPlaying) active.pause()
        } catch (_: Exception) {
        }
        playing = false
        onStateChanged(state())
    }

    fun next() {
        if (tracks.isEmpty()) return
        startTrack((currentIndex + 1) % tracks.size)
    }

    fun previous() {
        if (tracks.isEmpty()) return
        startTrack((currentIndex - 1 + tracks.size) % tracks.size)
    }

    /** 재생 위치(밀리초)를 이동합니다. 호스트/시크바에서 호출. */
    fun seekTo(positionMs: Long) {
        val active = player ?: return
        try {
            val duration = active.duration
            val target = positionMs.coerceIn(0L, if (duration > 0) duration.toLong() else Long.MAX_VALUE)
            active.seekTo(target, MediaPlayer.SEEK_CLOSEST)
        } catch (_: Exception) {
        }
        onStateChanged(state())
    }

    fun isPlaying(): Boolean = playing

    fun release() {
        releasePlayer()
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focusRequest = null
        currentIndex = -1
    }

    private fun startTrack(index: Int) {
        if (tracks.isEmpty()) return
        if (!requestFocus()) {
            onStateChanged(state())
            return
        }

        releasePlayer()
        currentIndex = index
        val fileName = tracks[index]

        try {
            val mediaPlayer = MediaPlayer()
            player = mediaPlayer
            mediaPlayer.setAudioAttributes(audioAttributes)
            applyAssetDataSource(mediaPlayer, fileName)
            mediaPlayer.setOnCompletionListener { completed ->
                Log.d(
                    TAG,
                    "완료: $fileName pos=${completed.currentPosition} dur=${completed.duration}"
                )
                next()
            }
            mediaPlayer.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer 오류 what=$what extra=$extra")
                playing = false
                onStateChanged(state())
                true
            }
            mediaPlayer.prepare()
            Log.d(TAG, "준비 완료: $fileName dur=${mediaPlayer.duration}ms")
            mediaPlayer.start()

            playing = true
            Log.d(TAG, "재생 시작: $fileName (${index + 1}/${tracks.size})")
        } catch (e: Exception) {
            Log.w(TAG, "트랙 준비 실패: $fileName", e)
            playing = false
            releasePlayer()
        }
        onStateChanged(state())
    }

    /**
     * assets 의 mp3 는 압축되지 않은 상태로 패키징되므로 파일 디스크립터로 바로 열 수 있습니다.
     * (혹시 압축된 경우를 대비해 cache 로 복사하는 경로를 둡니다.)
     */
    private fun applyAssetDataSource(mediaPlayer: MediaPlayer, fileName: String) {
        val assetPath = "$AUDIO_DIR/$fileName"
        try {
            // setDataSource(fd, ...) 는 내부에서 디스크립터를 복제하므로 블록 종료 시 닫아도 됩니다.
            appContext.assets.openFd(assetPath).use { afd ->
                mediaPlayer.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            return
        } catch (e: Exception) {
            Log.w(TAG, "openFd 실패 → cache 복사로 대체: $assetPath", e)
        }

        val cached = File(appContext.cacheDir, fileName)
        if (!cached.exists() || cached.length() == 0L) {
            appContext.assets.open(assetPath).use { input ->
                cached.outputStream().use { output -> input.copyTo(output) }
            }
        }
        mediaPlayer.setDataSource(cached.path)
    }

    private fun releasePlayer() {
        player?.let {
            runCatching { it.reset() }
            runCatching { it.release() }
        }
        player = null
        playing = false
    }

    private fun requestFocus(): Boolean {
        val request = focusRequest ?: AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener(focusListener)
            .setWillPauseWhenDucked(false)
            .build()
            .also { focusRequest = it }

        val result = audioManager.requestAudioFocus(request)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "오디오 포커스 획득 실패: $result")
            return false
        }
        return true
    }

    private fun currentTitle(): String =
        if (currentIndex in tracks.indices) {
            tracks[currentIndex].substringBeforeLast('.')
        } else {
            "재생할 곡 없음"
        }

    /** 파일명 '가수 - 제목' 을 가수/제목으로 분리합니다. */
    private fun currentArtistAndTitle(): Pair<String, String> {
        val name = if (currentIndex in tracks.indices) {
            tracks[currentIndex].substringBeforeLast('.')
        } else {
            return "RoadJ" to "재생할 곡 없음"
        }
        val separator = name.indexOf(" - ")
        return if (separator > 0) {
            name.substring(0, separator).trim() to name.substring(separator + 3).trim()
        } else {
            "RoadJ" to name.trim()
        }
    }

    private fun currentPositionMs(): Long =
        player?.let { runCatching { it.currentPosition.toLong() }.getOrDefault(0L) } ?: 0L

    private fun currentDurationMs(): Long =
        player?.let { runCatching { it.duration.toLong() }.getOrDefault(0L) } ?: 0L
}
