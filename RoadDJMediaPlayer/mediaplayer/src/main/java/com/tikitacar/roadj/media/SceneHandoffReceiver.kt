package com.tikitacar.roadj.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tikitacar.roadj.PlaybackController
import com.tikitacar.roadj.SceneHandoff

/**
 * RoadDJ Media Template 앱에서 넘어온 재생 상태를 받아 복원합니다.
 */
class SceneHandoffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val snapshot = SceneHandoff.read(intent) ?: return
        PlaybackController.initializeMedia(context)
        SceneController.initialize(context)
        SceneController.onHandoffReceived()
        PlaybackController.restoreSnapshot(snapshot)
        Log.d(
            "RoadDJScene",
            "RoadDJ Media Template → RoadDJ player 상태 수신: " +
                "${snapshot.file?.substringBeforeLast('.')} @${snapshot.positionMs}ms"
        )
    }
}
