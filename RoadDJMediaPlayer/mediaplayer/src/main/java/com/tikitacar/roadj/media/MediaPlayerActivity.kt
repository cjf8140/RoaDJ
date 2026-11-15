package com.tikitacar.roadj.media

import android.app.Activity
import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.tikitacar.roadj.LocalAudioPlayer
import com.tikitacar.roadj.PlaybackController
import com.tikitacar.roadj.SongTagFormatter

/**
 * CAL API 8 미만(이 에뮬레이터 포함)에서도 앨범 커버·곡 제목·진행 바를
 * 라이브로 보여주는 커스텀 미디어 화면.
 *
 * - 미디어 정보만 표시하고 위치/날씨/주변 정보는 표시하지 않습니다.
 * - CAL 템플릿을 사용하지 않으므로 5-step 쿼터 제한이 없습니다.
 * - CAL API 8 이상 차량에서는 기존 MediaPlaybackTemplate + CarAppService 를 그대로 사용합니다.
 */
class MediaPlayerActivity : Activity() {

    companion object {
        private const val TAG = "RoadDJMediaActivity"
        private const val REQUEST_CAR_SPEED = 2001
        private const val PERMISSION_CAR_SPEED = "android.car.permission.CAR_SPEED"
        private const val UI_TICK_MS = 500L
        private const val QUEUE_LIMIT = 4
    }

    private lateinit var cover: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var tagsView: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var positionView: TextView
    private lateinit var durationView: TextView
    private lateinit var playPauseButton: ImageButton
    private lateinit var prevButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var rewindButton: ImageButton
    private lateinit var forwardButton: ImageButton
    private lateinit var backButton: TextView
    private lateinit var languageButton: TextView
    private lateinit var queueContainer: LinearLayout
    private lateinit var playedCountView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var removeListener: (() -> Unit)? = null
    private var userSeeking = false
    private var speedPermissionRequested = false
    private var lastQueueSignature: String? = null
    private var lastTagText: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            render(PlaybackController.state())
            handler.postDelayed(this, UI_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_player)

        cover = findViewById(R.id.iv_cover)
        titleView = findViewById(R.id.tv_title)
        artistView = findViewById(R.id.tv_artist)
        tagsView = findViewById(R.id.tv_tags)
        seekBar = findViewById(R.id.seek_bar)
        positionView = findViewById(R.id.tv_position)
        durationView = findViewById(R.id.tv_duration)
        playPauseButton = findViewById(R.id.btn_play_pause)
        prevButton = findViewById(R.id.btn_prev)
        nextButton = findViewById(R.id.btn_next)
        rewindButton = findViewById(R.id.btn_rewind)
        forwardButton = findViewById(R.id.btn_forward)
        backButton = findViewById(R.id.btn_back)
        languageButton = findViewById(R.id.btn_language)
        queueContainer = findViewById(R.id.queue_container)
        playedCountView = findViewById(R.id.tv_played_count)

        cover.setImageResource(R.drawable.ic_roadj_cover)

        DrivingDataCollector.requestGearPermission(this)

        val app = application as MediaPlayerApplication
        app.mediaSessionController.onFirstPlay = { requestSpeedPermission() }

        backButton.setOnClickListener { finish() }
        languageButton.setOnClickListener { showLanguageDialog() }
        updateLanguageButton()
        playPauseButton.setOnClickListener {
            PlaybackController.toggle()
            requestSpeedPermission()
            DrivingDataCollector.requestGearPermission(this)
            render(PlaybackController.state())
        }
        prevButton.setOnClickListener {
            PlaybackController.previous()
            render(PlaybackController.state())
        }
        nextButton.setOnClickListener {
            PlaybackController.next()
            render(PlaybackController.state())
        }
        rewindButton.setOnClickListener { seekBy(-10_000L) }
        forwardButton.setOnClickListener { seekBy(10_000L) }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    positionView.text = MediaDisplay.formatTime(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userSeeking = false
                seekBar?.let { PlaybackController.seekTo(it.progress.toLong()) }
                render(PlaybackController.state())
            }
        })

        removeListener = PlaybackController.addListener { state ->
            runOnUiThread { render(state) }
        }

        render(PlaybackController.state())
        handler.post(ticker)
        Log.d(TAG, "커스텀 미디어 화면 시작")
    }

    private fun render(state: LocalAudioPlayer.State) {
        val songTitle = state.songTitle.ifBlank { state.title }
        titleView.text = songTitle
        artistView.text = MediaDisplay.artistWithMoodTag(state.artist)

        val durationMs = if (state.durationMs > 0L) {
            state.durationMs
        } else {
            MediaDisplay.DEFAULT_DURATION_MS
        }
        if (seekBar.max != durationMs.toInt()) {
            seekBar.max = durationMs.toInt()
        }
        if (!userSeeking) {
            val progress = state.positionMs.coerceIn(0L, durationMs).toInt()
            seekBar.progress = progress
            positionView.text = MediaDisplay.formatTime(progress.toLong())
        }
        durationView.text = MediaDisplay.formatTime(durationMs)

        playPauseButton.setImageResource(
            if (state.playing) {
                android.R.drawable.ic_media_pause
            } else {
                android.R.drawable.ic_media_play
            }
        )
        playPauseButton.contentDescription = getString(
            if (state.playing) R.string.media_pause else R.string.media_play
        )

        updateTags()
        updateQueue(state.total)
    }

    /** 언어 버튼: 검은색 목록 다이얼로그에서 언어를 선택합니다. */
    private fun showLanguageDialog() {
        val options = PlaybackController.languageOptions
        val current = PlaybackController.carState().lang

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_language)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val container = dialog.findViewById<LinearLayout>(R.id.language_options)
        options.forEach { lang ->
            val label = PlaybackController.languageDisplayName(lang)
            val row = TextView(this).apply {
                text = if (lang == current) "✓  $label" else "    $label"
                textSize = 20f
                setTextColor(if (lang == current) Color.WHITE else Color.parseColor("#D0D0D0"))
                setPadding(dp(20), dp(16), dp(20), dp(16))
                isClickable = true
                setBackgroundColor(
                    if (lang == current) Color.parseColor("#262626") else Color.TRANSPARENT
                )
                setOnClickListener {
                    Log.d(TAG, "곡 언어 선택: $lang")
                    PlaybackController.setLanguage(lang)
                    updateLanguageButton()
                    lastQueueSignature = null
                    lastTagText = null
                    render(PlaybackController.state())
                    dialog.dismiss()
                }
            }
            container.addView(row)
        }

        dialog.findViewById<TextView>(R.id.btn_cancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setLayout(dp(520), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun updateLanguageButton() {
        languageButton.text = "언어 · " +
            PlaybackController.languageDisplayName(PlaybackController.carState().lang)
    }

    /** 곡별 태그 중 현재 주행 상황과 일치하는 태그만 최대 3개 표시합니다. */
    private fun updateTags() {
        val file = PlaybackController.currentFile()
        val carState = PlaybackController.carState()
        val tags = if (file != null) {
            SongTagFormatter.tagsFor(this, file, carState)
        } else {
            emptyList()
        }

        val text = tags.joinToString(" ") { "#$it" }
        if (text == lastTagText) return
        lastTagText = text

        if (text.isEmpty()) {
            tagsView.visibility = View.GONE
        } else {
            tagsView.text = text
            tagsView.visibility = View.VISIBLE
        }
        Log.d(
            TAG,
            "곡 태그(현재 상황 일치) ${file?.substringBeforeLast('.')}: $text"
        )
    }

    /**
     * 현재 순위 순서에서 아직 재생하지 않은 곡을 "다음 곡" 목록으로 표시합니다.
     * 목록이 바뀔 때만 다시 그립니다.
     */
    private fun updateQueue(total: Int) {
        playedCountView.text =
            "재생한 곡 ${PlaybackController.playedCountInPlaylist()}/$total"

        val upcoming = PlaybackController.upcomingFiles(QUEUE_LIMIT)
        val signature = upcoming.joinToString("|")
        if (signature == lastQueueSignature) return
        lastQueueSignature = signature

        queueContainer.removeAllViews()
        if (upcoming.isEmpty()) {
            queueContainer.addView(
                createQueueRow(
                    "다음 곡 없음 · 모든 곡 재생 완료",
                    highlight = false,
                    fileName = null
                )
            )
            return
        }

        upcoming.forEachIndexed { index, file ->
            queueContainer.addView(
                createQueueRow(
                    "${index + 1}. ${file.substringBeforeLast('.')}",
                    highlight = index == 0,
                    fileName = file
                )
            )
        }
    }

    private fun createQueueRow(
        textValue: String,
        highlight: Boolean,
        fileName: String?
    ): TextView =
        TextView(this).apply {
            text = textValue
            textSize = 16f
            setTextColor(Color.parseColor(if (highlight) "#FFFFFF" else "#B3B3B3"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(5), 0, dp(5))
            if (fileName != null) {
                isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    Log.d(TAG, "다음 곡 목록 선택: ${fileName.substringBeforeLast('.')}")
                    PlaybackController.playSelected(fileName)
                    lastQueueSignature = null
                    render(PlaybackController.state())
                }
            }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun seekBy(deltaMs: Long) {
        val state = PlaybackController.state()
        val durationMs = if (state.durationMs > 0L) {
            state.durationMs
        } else {
            MediaDisplay.DEFAULT_DURATION_MS
        }
        val target = (state.positionMs + deltaMs).coerceIn(0L, durationMs)
        PlaybackController.seekTo(target)
        render(PlaybackController.state())
    }

    private fun requestSpeedPermission() {
        if (ContextCompat.checkSelfPermission(this, PERMISSION_CAR_SPEED) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            DrivingDataCollector.onSpeedPermissionGranted()
            return
        }
        if (speedPermissionRequested) return
        speedPermissionRequested = true
        try {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(PERMISSION_CAR_SPEED),
                REQUEST_CAR_SPEED
            )
        } catch (e: Exception) {
            Log.w(TAG, "차량 속도 권한 요청 실패", e)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CAR_SPEED -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    DrivingDataCollector.onSpeedPermissionGranted()
                } else {
                    Log.w(TAG, "차량 속도 권한 거부")
                }
            }

            DrivingDataCollector.REQUEST_CAR_POWERTRAIN -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    DrivingDataCollector.onGearPermissionGranted()
                } else {
                    Log.w(TAG, "차량 기어 권한 거부")
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        SceneController.markSceneVisible(SceneController.Scene.PLAYER)
    }

    override fun onStop() {
        SceneController.deactivate(SceneController.Scene.PLAYER)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        removeListener?.invoke()
        removeListener = null
        // 재생과 MediaSession 은 앱 프로세스가 유지하므로 여기서 해제하지 않습니다.
        super.onDestroy()
    }
}
