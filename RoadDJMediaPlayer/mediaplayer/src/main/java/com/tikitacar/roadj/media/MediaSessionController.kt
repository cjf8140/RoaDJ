package com.tikitacar.roadj.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import com.tikitacar.roadj.PlaybackController

/**
 * RoadDJ media player 의 MediaSession.
 * [PlaybackController] 의 재생 상태를 MediaSession 으로 노출해
 * MediaPlaybackTemplate/시스템 미디어 컨트롤이 제어할 수 있게 합니다.
 */
class MediaSessionController(
    context: Context,
    private val onFirstPlay: (() -> Unit)? = null
) {

    companion object {
        private const val TAG = "RoadDJMediaSession"
        private const val TICK_MS = 1_000L

        private const val ACTIONS = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    val session: MediaSessionCompat = MediaSessionCompat(context, "RoadDJMediaSession").apply {
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
        Log.d(TAG, "MediaSession 생성")
    }

    fun release() {
        mainHandler.removeCallbacks(ticker)
        runCatching { session.release() }
        Log.d(TAG, "MediaSession 해제")
    }

    private fun publish(forceMetadata: Boolean) {
        val state = PlaybackController.state()

        if (forceMetadata ||
            lastTitle != state.songTitle ||
            lastArtist != state.artist ||
            lastDuration != state.durationMs
        ) {
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.songTitle)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, state.artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "RoadDJ media player")
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.durationMs)
                    .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, state.position.toLong())
                    .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, state.total.toLong())
                    .build()
            )
            lastTitle = state.songTitle
            lastArtist = state.artist
            lastDuration = state.durationMs
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
