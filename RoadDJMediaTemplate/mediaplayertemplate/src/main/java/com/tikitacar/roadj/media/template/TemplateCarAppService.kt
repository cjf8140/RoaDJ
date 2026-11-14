package com.tikitacar.roadj.media.template

import android.content.Intent
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.media.MediaPlaybackManager
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.tikitacar.roadj.PlaybackController

/**
 * RoadDJ media player 차량 서비스.
 *
 * 미디어 템플릿 세션을 만들고, 앱 공용 MediaSession 토큰을 호스트에 등록합니다.
 * 데이터 수집(위치/속도/날씨/주변)은 [DrivingDataCollector] 가 담당합니다.
 */
class TemplateCarAppService : CarAppService() {

    companion object {
        private const val TAG = "RoadDJTemplateCarApp"
    }

    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session {
        PlaybackController.initialize(applicationContext)

        return object : Session() {
            init {
                lifecycle.addObserver(
                    LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_CREATE) {
                            val app = carContext.applicationContext as TemplatePlayerApplication
                            val controller = app.mediaSessionController
                            controller.onFirstPlay = {
                                DrivingDataCollector.requestSpeedPermission(carContext)
                            }
                            registerPlaybackToken(carContext, controller)
                        }
                    }
                )
            }

            override fun onCreateScreen(intent: Intent): Screen {
                PlaybackController.initialize(carContext)
                return TemplatePlaybackScreen(carContext)
            }
        }
    }

    private fun registerPlaybackToken(
        carContext: CarContext,
        controller: TemplateMediaSessionController
    ) {
        try {
            val manager = carContext.getCarService(CarContext.MEDIA_PLAYBACK_SERVICE)
                as? MediaPlaybackManager
            if (manager != null) {
                manager.registerMediaPlaybackToken(controller.session.sessionToken)
                Log.d(TAG, "MediaSession 토큰 등록 완료")
            } else {
                Log.w(TAG, "MediaPlaybackManager 없음")
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaSession 토큰 등록 실패", e)
        }
    }
}
