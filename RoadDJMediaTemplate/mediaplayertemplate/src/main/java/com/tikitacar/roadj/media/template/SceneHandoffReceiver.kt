package com.tikitacar.roadj.media.template

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tikitacar.roadj.PlaybackController
import com.tikitacar.roadj.SceneHandoff

/**
 * RoadDJ player 앱에서 넘어온 재생 상태를 받아 복원합니다.
 */
class SceneHandoffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val snapshot = SceneHandoff.read(intent) ?: return
        PlaybackController.initialize(context)
        TemplateSceneController.initialize(context)
        TemplateSceneController.onHandoffReceived()
        PlaybackController.restoreSnapshot(snapshot)
        Log.d(
            "RoadDJTemplateScene",
            "RoadDJ player → RoadDJ Media Template 상태 수신: " +
                "${snapshot.file?.substringBeforeLast('.')} @${snapshot.positionMs}ms"
        )
    }
}
