package com.tikitacar.roadj.media.template

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import com.tikitacar.roadj.PlaybackController
import com.tikitacar.roadj.SongTagFormatter

/**
 * RoadDJ media player 의 MediaSession.
 * [PlaybackController] 의 재생 상태를 MediaSession 으로 노출해
 * MediaPlaybackTemplate/시스템 미디어 컨트롤이 제어할 수 있게 합니다.
 */
class TemplateMediaSessionController(context: Context) {

    companion object {
        private const val TAG = "RoadDJTemplateSession"
        private const val TICK_MS = 1_000L

        private const val ACTIONS = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO
    }

    /** 첫 재생 시 1회 호출됩니다. (차량 속도 권한 요청 등) */
    var onFirstPlay: (() -> Unit)? = null

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 곡별 커버가 없을 때 항상 사용하는 기본 RoaDJ 앨범 커버 */
    private val defaultAlbumArt: Bitmap? = loadDefaultAlbumArt()

    val session: MediaSessionCompat = MediaSessionCompat(context, "RoadDJTemplateSession").apply {
        setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        isActive = true
    }

    private var lastTitle: String? = null
    private var lastArtist: String? = null
    private var lastDuration: Long = -1L
    private var firstPlayNotified = false

    private val ticker = object : Runnable {
        override fun run() {
            publish(forceMetadata = false)
            mainHandler.postDelayed(this, TICK_MS)
        }
    }

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            notifyFirstPlay()
            PlaybackController.play()
        }

        override fun onPause() {
            PlaybackController.pause()
        }

        override fun onStop() {
            PlaybackController.pause()
        }

        override fun onSkipToNext() {
            notifyFirstPlay()
            PlaybackController.next()
        }

        override fun onSkipToPrevious() {
            PlaybackController.previous()
        }

        override fun onSeekTo(pos: Long) {
            PlaybackController.seekTo(pos)
        }
    }

    private fun notifyFirstPlay() {
        if (firstPlayNotified) return
        firstPlayNotified = true
        onFirstPlay?.invoke()
    }

    init {
        session.setCallback(callback, mainHandler)
        publish(forceMetadata = true)
        mainHandler.post(ticker)
        Log.d(TAG, "MediaSession 생성 (기본 커버=${defaultAlbumArt != null})")
    }

    fun release() {
        mainHandler.removeCallbacks(ticker)
        runCatching { session.release() }
        Log.d(TAG, "MediaSession 해제")
    }

    private fun loadDefaultAlbumArt(): Bitmap? =
        runCatching {
            BitmapFactory.decodeResource(
                appContext.resources,
                R.drawable.ic_roadj_cover
            )
        }.onFailure {
            Log.w(TAG, "기본 앨범 커버 로드 실패", it)
        }.getOrNull()

    /**
     * 가수명 뒤에 현재 상황과 일치하는 곡 태그를 붙입니다.
     * (MediaPlaybackTemplate 의 중앙 슬롯에는 별도 태그 줄이 없어 artist 필드에 표시)
     */
    private fun buildDisplayArtist(baseArtist: String): String {
        val file = PlaybackController.currentFile()
        val tags = if (file != null) {
            SongTagFormatter.tagsFor(
                appContext,
                file,
                PlaybackController.carState()
            )
        } else {
            emptyList()
        }

        val artist = baseArtist.ifBlank { "RoaDJ" }
        return if (tags.isNotEmpty()) {
            "$artist • ${tags.joinToString(" ") { "#$it" }}"
        } else {
            MediaDisplay.artistWithMoodTag(artist)
        }
    }

    private fun publish(forceMetadata: Boolean) {
        val state = PlaybackController.state()
        val displayArtist = buildDisplayArtist(state.artist)
        val durationMs = if (state.durationMs > 0L) {
            state.durationMs
        } else {
            MediaDisplay.DEFAULT_DURATION_MS
        }

        if (forceMetadata ||
            lastTitle != state.songTitle ||
            lastArtist != displayArtist ||
            lastDuration != durationMs
        ) {
            val metadataBuilder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.songTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, displayArtist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "RoaDJ")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
                .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, state.position.toLong())
                .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, state.total.toLong())

            // 곡에 별도 커버가 없으므로 항상 기본 RoaDJ 로고를 넣습니다.
            defaultAlbumArt?.let { bitmap ->
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
            }

            session.setMetadata(metadataBuilder.build())
            lastTitle = state.songTitle
            lastArtist = displayArtist
            lastDuration = durationMs

            Log.d(
                TAG,
                "메타데이터 갱신: title=${state.songTitle}, artist=$displayArtist, " +
                    "duration=${durationMs}ms, albumArt=${defaultAlbumArt != null}"
            )
        }

        val playbackState = when {
            state.playing -> PlaybackStateCompat.STATE_PLAYING
            state.durationMs > 0L -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_NONE
        }

        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(ACTIONS)
                .setState(
                    playbackState,
                    state.positionMs,
                    if (state.playing) 1.0f else 0.0f,
                    SystemClock.elapsedRealtime()
                )
                .build()
        )
    }
}
