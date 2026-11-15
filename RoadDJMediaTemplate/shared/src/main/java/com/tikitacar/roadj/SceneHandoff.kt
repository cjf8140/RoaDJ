package com.tikitacar.roadj

import android.content.Context
import android.content.Intent

/** Park/Neutral 씬과 Drive 씬 사이에 넘기는 재생 상태 */
data class SceneSnapshot(
    val lang: String,
    val file: String?,
    val positionMs: Long,
    val playing: Boolean,
    val playedFiles: List<String>
)

/**
 * RoadDJ player 앱과 RoadDJ Media Template 앱 사이의 상태 전달.
 *
 * 두 앱은 서로 다른 프로세스이므로, 씬을 넘길 때 현재 곡/재생 위치/재생 여부/
 * 선택 언어/재생 이력을 명시적 broadcast 로 전달합니다.
 */
object SceneHandoff {

    const val ACTION = "com.tikitacar.roadj.action.SCENE_HANDOFF"
    const val REQUEST_ACTION = "com.tikitacar.roadj.action.SCENE_STATE_REQUEST"

    const val SHOW_MEDIA_PLAYBACK = "androidx.car.app.media.action.SHOW_MEDIA_PLAYBACK"

    const val PLAYER_PACKAGE = "com.tikitacar.roadj.media"
    const val TEMPLATE_PACKAGE = "com.tikitacar.roadj.media.template"

    const val PLAYER_ACTIVITY = "com.tikitacar.roadj.media.MediaPlayerActivity"
    const val TEMPLATE_ACTIVITY = "androidx.car.app.activity.CarAppActivity"

    const val PLAYER_RECEIVER = "com.tikitacar.roadj.media.SceneHandoffReceiver"
    const val TEMPLATE_RECEIVER = "com.tikitacar.roadj.media.template.SceneHandoffReceiver"

    const val PLAYER_STATE_REQUEST_RECEIVER =
        "com.tikitacar.roadj.media.SceneStateRequestReceiver"

    private const val EXTRA_LANG = "lang"
    private const val EXTRA_FILE = "file"
    private const val EXTRA_POSITION = "position"
    private const val EXTRA_PLAYING = "playing"
    private const val EXTRA_PLAYED = "played"

    /** 상대 앱에 현재 재생 상태를 요청합니다. (수동으로 템플릿 앱을 열 때 사용) */
    fun request(
        context: Context,
        targetPackage: String,
        targetReceiver: String
    ) {
        val intent = Intent(REQUEST_ACTION).setClassName(targetPackage, targetReceiver)
        context.sendBroadcast(intent)
    }

    fun send(
        context: Context,
        targetPackage: String,
        targetReceiver: String,
        snapshot: SceneSnapshot
    ) {
        val intent = Intent(ACTION)
            .setClassName(targetPackage, targetReceiver)
            .putExtra(EXTRA_LANG, snapshot.lang)
            .putExtra(EXTRA_FILE, snapshot.file)
            .putExtra(EXTRA_POSITION, snapshot.positionMs)
            .putExtra(EXTRA_PLAYING, snapshot.playing)
            .putStringArrayListExtra(EXTRA_PLAYED, ArrayList(snapshot.playedFiles))
        context.sendBroadcast(intent)
    }

    fun read(intent: Intent): SceneSnapshot? {
        if (intent.action != ACTION) return null
        if (!intent.hasExtra(EXTRA_LANG)) return null
        return SceneSnapshot(
            lang = intent.getStringExtra(EXTRA_LANG) ?: "all",
            file = intent.getStringExtra(EXTRA_FILE),
            positionMs = intent.getLongExtra(EXTRA_POSITION, 0L),
            playing = intent.getBooleanExtra(EXTRA_PLAYING, false),
            playedFiles = intent.getStringArrayListExtra(EXTRA_PLAYED).orEmpty()
        )
    }
}
