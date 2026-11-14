package com.tikitacar.roadj.media

import android.app.Application
import com.tikitacar.roadj.PlaybackController

/**
 * RoadDJ media player 프로세스 시작 시 재생 컨트롤러와 데이터 수집기를 초기화합니다.
 * 화면 표시 없이 NaviHelper/속도/날씨/Overpass 데이터를 병행 수신합니다.
 */
class MediaPlayerApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        PlaybackController.initialize(this)
        DrivingDataCollector.start(this)
    }
}
