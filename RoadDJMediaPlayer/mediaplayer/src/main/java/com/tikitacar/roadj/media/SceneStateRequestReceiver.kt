package com.tikitacar.roadj.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tikitacar.roadj.PlaybackController
import com.tikitacar.roadj.SceneHandoff

/**
 * RoadDJ Media Template 앱이 수동으로 열릴 때 현재 재생 상태를 요청하면
 * 현재 곡/위치/언어를 handoff 로 돌려줍니다.
 */
class SceneStateRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SceneHandoff.REQUEST_ACTION) return

        PlaybackController.initializeMedia(context)
        SceneController.initialize(context)

        val snapshot = if (PlaybackController.hasActiveTrack()) {
            PlaybackController.snapshot()
        } else {
            PlaybackController.lastSavedSnapshot() ?: PlaybackController.snapshot()
        }
        val file = snapshot.file
        if (file.isNullOrBlank()) {
            Log.d("RoadDJScene", "상태 요청 수신: 재생 중인 곡 없음")
            return
        }

        Log.d(
            "RoadDJScene",
            "상태 요청 수신 → RoadDJ Media Template 로 전달: " +
                "${file.substringBeforeLast('.')} @${snapshot.positionMs}ms"
        )
        PlaybackController.pause()
        SceneHandoff.send(
            context,
            SceneHandoff.TEMPLATE_PACKAGE,
            SceneHandoff.TEMPLATE_RECEIVER,
            snapshot
        )
    }
}
