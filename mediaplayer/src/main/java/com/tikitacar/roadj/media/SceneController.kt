package com.tikitacar.roadj.media

import android.car.VehicleGear
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.activity.CarAppActivity

/**
 * RoadDJ 한 앱 안에서 두 씬을 교체합니다.
 *
 * - Park / Neutral: [MediaPlayerActivity] — 커스텀 플레이어
 * - Drive / Reverse / 주행 기어: [CarAppActivity] — PaneTemplate/MediaPlaybackTemplate
 *
 * 두 씬은 같은 앱, 같은 프로세스의 PlaybackController 를 공유하므로
 * 선택 언어, 현재 곡, 재생 위치, 재생 이력이 그대로 유지됩니다.
 */
object SceneController {

    private const val TAG = "RoadDJScene"

    private const val SHOW_MEDIA_PLAYBACK_ACTION =
        "androidx.car.app.media.action.SHOW_MEDIA_PLAYBACK"

    /** 기어 변경이 연속으로 들어올 때 화면이 깜빡이지 않도록 안정화 시간을 둡니다. */
    private const val SETTLE_MS = 1_500L

    /** 기어 정보를 사용할 수 없을 때만 사용하는 속도 기반 폴백 */
    private const val SPEED_DRIVE_KMH = 5f

    enum class Scene { PLAYER, TEMPLATE }

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var currentScene: Scene = Scene.PLAYER

    @Volatile
    private var sceneActive = false

    @Volatile
    private var gearAvailable = false

    private var lastGear: Int? = null
    private var pendingScene: Scene? = null

    private val switchRunnable = Runnable {
        val target = pendingScene ?: return@Runnable
        pendingScene = null
        if (sceneActive && target != currentScene) {
            switchTo(target)
        }
    }

    fun initialize(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            Log.d(TAG, "SceneController 초기화")
        }
    }

    /** 실제로 화면이 만들어진 시점에 호출합니다. */
    fun markSceneVisible(scene: Scene) {
        currentScene = scene
        sceneActive = true
        Log.d(TAG, "현재 씬: $scene")
        evaluate(lastGear)
    }

    /** 화면이 백그라운드로 내려가면 씬 전환을 멈춥니다. */
    fun deactivate(scene: Scene) {
        if (currentScene != scene) return
        sceneActive = false
        pendingScene = null
        handler.removeCallbacks(switchRunnable)
        Log.d(TAG, "씬 비활성화: $scene")
    }

    fun setGearAvailable(available: Boolean) {
        gearAvailable = available
    }

    fun onGearChanged(gear: Int) {
        lastGear = gear
        if (!sceneActive) return
        evaluate(gear)
    }

    /** 기어 정보가 없을 때만 사용하는 보조 판정입니다. */
    fun onSpeedChanged(speedKmh: Float) {
        if (!sceneActive || gearAvailable) return
        if (speedKmh >= SPEED_DRIVE_KMH) {
            schedule(Scene.TEMPLATE)
        }
    }

    private fun evaluate(gear: Int?) {
        val target = when (gear) {
            VehicleGear.GEAR_PARK,
            VehicleGear.GEAR_NEUTRAL -> Scene.PLAYER

            VehicleGear.GEAR_DRIVE,
            VehicleGear.GEAR_REVERSE -> Scene.TEMPLATE

            in VehicleGear.GEAR_FIRST..VehicleGear.GEAR_NINTH -> Scene.TEMPLATE

            else -> null
        } ?: return

        Log.d(TAG, "기어=$gear → 목표 씬=$target")
        schedule(target)
    }

    private fun schedule(target: Scene) {
        if (target == currentScene) {
            pendingScene = null
            handler.removeCallbacks(switchRunnable)
            return
        }
        if (pendingScene == target) return

        pendingScene = target
        handler.removeCallbacks(switchRunnable)
        handler.postDelayed(switchRunnable, SETTLE_MS)
    }

    private fun switchTo(target: Scene) {
        val context = appContext ?: return
        sceneActive = false

        val intent = when (target) {
            Scene.PLAYER -> Intent(context, MediaPlayerActivity::class.java)

            Scene.TEMPLATE -> Intent(context, CarAppActivity::class.java)
                .setAction(SHOW_MEDIA_PLAYBACK_ACTION)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        try {
            context.startActivity(intent)
            currentScene = target
            Log.d(TAG, "씬 전환: $target")
        } catch (e: Exception) {
            Log.e(TAG, "씬 전환 실패: $target", e)
        }
    }
}
