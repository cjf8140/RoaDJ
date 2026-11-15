package com.tikitacar.roadj.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.annotations.ExperimentalCarApi
import androidx.car.app.media.model.MediaPlaybackTemplate
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tikitacar.roadj.PlaybackController
import com.tikitacar.roadj.SongTagFormatter

/**
 * 주행(Drive) 씬.
 *
 * RoadDJ player 앱 안에서 RoadDJ Media Template 과 동일한 PaneTemplate 화면을
 * 렌더링합니다. 같은 프로세스의 [PlaybackController] 를 사용하므로
 * Park/Neutral 씬(RoadDJ player)에서 선택한 언어와 현재 곡이 그대로 이어집니다.
 *
 * - CAL API 8 이상: [MediaPlaybackTemplate]
 * - CAL API 4~7: [PaneTemplate] 폴백 (Row 제목/개수 고정, 텍스트만 갱신)
 */
@OptIn(ExperimentalCarApi::class)
class MediaPlaybackScreen(carContext: CarContext) :
    Screen(carContext), DefaultLifecycleObserver {

    private companion object {
        const val MEDIA_TEMPLATE_API_LEVEL = 8
        const val ROW_TITLE = "지금 재생 중"
        const val TEMPLATE_TITLE = "RoaDJ"
        const val UPDATE_MS = 1_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastSignature: String? = null

    private val cover: Bitmap? by lazy {
        runCatching {
            BitmapFactory.decodeResource(carContext.resources, R.drawable.ic_roadj_cover)
        }.getOrNull()
    }

    private val ticker = object : Runnable {
        override fun run() {
            val signature = currentSignature()
            if (signature != lastSignature) {
                lastSignature = signature
                invalidate()
            }
            handler.postDelayed(this, UPDATE_MS)
        }
    }

    init {
        PlaybackController.initializeMedia(carContext)
        SceneController.initialize(carContext)
        DrivingDataCollector.requestGearPermission(carContext)
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        SceneController.markSceneVisible(SceneController.Scene.TEMPLATE)
    }

    override fun onStop(owner: LifecycleOwner) {
        SceneController.deactivate(SceneController.Scene.TEMPLATE)
    }

    override fun onCreate(owner: LifecycleOwner) {
        if (carContext.carAppApiLevel < MEDIA_TEMPLATE_API_LEVEL) {
            lastSignature = currentSignature()
            handler.post(ticker)
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        handler.removeCallbacks(ticker)
        SceneController.deactivate(SceneController.Scene.TEMPLATE)
    }

    override fun onGetTemplate(): Template =
        if (carContext.carAppApiLevel >= MEDIA_TEMPLATE_API_LEVEL) {
            buildMediaPlaybackTemplate()
        } else {
            buildPaneTemplate()
        }

    private fun buildMediaPlaybackTemplate(): Template {
        val header = Header.Builder()
            .setStartHeaderAction(Action.BACK)
            .setTitle(TEMPLATE_TITLE)
            .build()

        return MediaPlaybackTemplate.Builder()
            .setHeader(header)
            .build()
    }

    private fun buildPaneTemplate(): Template {
        val state = PlaybackController.state()
        val songTitle = state.songTitle.ifBlank { state.title }
        val tags = currentTags()

        val artistText = buildString {
            append(state.artist.ifBlank { "RoaDJ" })
            if (tags.isNotEmpty()) {
                append(" • ")
                append(tags.joinToString(" ") { "#$it" })
            }
        }
        val statusText = if (state.playing) "재생 중" else "일시정지"

        val rowBuilder = Row.Builder()
            .setTitle(ROW_TITLE)
            .addText(songTitle)
            .addText("$artistText · $statusText")

        cover?.let { bitmap ->
            rowBuilder.setImage(
                CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
            )
        }

        val pane = Pane.Builder()
            .addRow(rowBuilder.build())
            .build()

        val actions = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(mediaIcon(android.R.drawable.ic_media_play))
                    .setOnClickListener {
                        PlaybackController.toggle()
                        DrivingDataCollector.requestSpeedPermission(carContext)
                        invalidate()
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(mediaIcon(android.R.drawable.ic_media_next))
                    .setOnClickListener {
                        PlaybackController.next()
                        invalidate()
                    }
                    .build()
            )
            .build()

        return PaneTemplate.Builder(pane)
            .setTitle(TEMPLATE_TITLE)
            .setHeaderAction(Action.BACK)
            .setActionStrip(actions)
            .build()
    }

    private fun mediaIcon(resourceId: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, resourceId)).build()

    private fun currentTags(): List<String> {
        val file = PlaybackController.currentFile() ?: return emptyList()
        return SongTagFormatter.tagsFor(
            carContext,
            file,
            PlaybackController.carState()
        )
    }

    private fun currentSignature(): String {
        val state = PlaybackController.state()
        val songTitle = state.songTitle.ifBlank { state.title }
        val artist = state.artist
        val tags = currentTags().joinToString(",")
        return "$songTitle|$artist|$tags|${PlaybackController.currentFile()}|${state.playing}"
    }
}
