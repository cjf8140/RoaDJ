package com.tikitacar.roadj.media.template

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
 * RoadDJ Media Template 의 재생 화면.
 *
 * - CAL API 8 이상: [MediaPlaybackTemplate]
 *   호스트가 MediaSession 메타데이터로 앨범 커버/곡 제목/진행 바를 렌더링합니다.
 * - CAL API 4~7: [PaneTemplate] 폴백
 *   PaneTemplate 의 템플릿 제목과 Row 제목을 고정해 두면
 *   Row 의 텍스트/이미지 변경은 refresh 로 처리되어 5-step 쿼터를 소모하지 않습니다.
 *   따라서 이 호스트에서도 현재 곡 이름과 현재 상황 태그를 갱신해 표시할 수 있습니다.
 */
@OptIn(ExperimentalCarApi::class)
class TemplatePlaybackScreen(carContext: CarContext) :
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
        PlaybackController.initialize(carContext)
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        if (carContext.carAppApiLevel < MEDIA_TEMPLATE_API_LEVEL) {
            lastSignature = currentSignature()
            handler.post(ticker)
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        handler.removeCallbacks(ticker)
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

    /**
     * API 4~7 용 폴백.
     * 템플릿 제목/Row 제목/Row 개수를 고정해 두고 Row 텍스트만 갱신합니다.
     */
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

        // API 4 제약: ActionStrip 은 최대 2개 action 만 허용합니다.
        // 따라서 재생/일시정지 + 다음 곡만 아이콘으로 제공합니다.
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
