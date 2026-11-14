package com.tikitacar.roadj.media

import android.util.Log
import android.widget.Toast
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.annotations.ExperimentalCarApi
import androidx.car.app.media.model.MediaPlaybackTemplate
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import com.tikitacar.roadj.PlaybackController

/**
 * RoadDJ media player 의 재생 화면.
 *
 * - CAL API 8 이상: [MediaPlaybackTemplate]
 *   곡 정보/재생 컨트롤은 호스트가 MediaSession 상태로 직접 렌더링하므로
 *   곡이 바뀌어도 템플릿을 다시 보내지 않아 안전합니다.
 *
 * - CAL API 4~7: [MessageTemplate] 폴백
 *   MessageTemplate 은 제목/본문이 바뀌면 템플릿 스텝 쿼터(최대 5개)를 소모합니다.
 *   곡이 바뀔 때마다 화면을 갱신하면 쿼터가 소진되어 앱이 종료되므로,
 *   폴백 화면은 "고정된 안내 + 재생 제어 버튼"으로만 구성하고 invalidate 하지 않습니다.
 */
@OptIn(ExperimentalCarApi::class)
class MediaPlaybackScreen(carContext: CarContext) : Screen(carContext) {

    init {
        PlaybackController.initialize(carContext)
    }

    override fun onGetTemplate(): Template =
        if (carContext.carAppApiLevel >= MEDIA_TEMPLATE_API_LEVEL) {
            buildMediaPlaybackTemplate()
        } else {
            buildFallbackTemplate()
        }

    private fun buildMediaPlaybackTemplate(): Template {
        val header = Header.Builder()
            .setStartHeaderAction(Action.BACK)
            .setTitle("RoadDJ media player")
            .build()

        return MediaPlaybackTemplate.Builder()
            .setHeader(header)
            .build()
    }

    /**
     * 구형 호스트(CAL API 4~7)용 고정 화면.
     * 템플릿을 다시 보내지 않으므로 5-step 쿼터를 소모하지 않습니다.
     */
    private fun buildFallbackTemplate(): Template {
        return MessageTemplate.Builder(
            "차량 미디어 키 또는 아래 버튼으로 재생을 제어하세요.\n" +
                "곡 순서는 위치·날씨·속도 데이터에 맞춰 자동으로 정렬됩니다."
        )
            .setTitle("RoadDJ media player")
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle("재생/일시정지")
                    .setOnClickListener {
                        val wasPlaying = PlaybackController.state().playing
                        Log.d(TAG, "폴백 버튼: 재생/일시정지 (wasPlaying=$wasPlaying)")
                        try {
                            // 화면이 만들어진 직후 첫 클릭이 와도 확실히 초기화되도록 보장합니다.
                            PlaybackController.initialize(carContext)
                            PlaybackController.toggle()
                            showToast(if (wasPlaying) "일시정지" else "재생")
                            // 첫 조작 시점에 속도 권한을 요청해 앱 시작 화면을 가리지 않게 합니다.
                            DrivingDataCollector.requestSpeedPermission(carContext)
                        } catch (e: Exception) {
                            Log.e(TAG, "재생/일시정지 실행 실패", e)
                            showToast("재생 제어 실패")
                        }
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("다음 곡")
                    .setOnClickListener {
                        Log.d(TAG, "폴백 버튼: 다음 곡")
                        try {
                            PlaybackController.initialize(carContext)
                            PlaybackController.next()
                            showToast("다음 곡")
                            DrivingDataCollector.requestSpeedPermission(carContext)
                        } catch (e: Exception) {
                            Log.e(TAG, "다음 곡 실행 실패", e)
                            showToast("다음 곡 실패")
                        }
                    }
                    .build()
            )
            .build()
    }

    /** 호스트 화면에 짧은 토스트를 표시합니다. (템플릿 쿼터와 무관) */
    private fun showToast(text: String) {
        try {
            val appManager =
                carContext.getCarService(CarContext.APP_SERVICE) as AppManager
            appManager.showToast(text, Toast.LENGTH_SHORT)
        } catch (e: Exception) {
            Log.w(TAG, "토스트 표시 실패: $text", e)
        }
    }

    private companion object {
        const val MEDIA_TEMPLATE_API_LEVEL = 8
        const val TAG = "RoadDJMediaScreen"
    }
}
