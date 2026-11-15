package com.tikitacar.roadj.media

import android.car.VehicleGear
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.tikitacar.roadj.PlaybackController
import com.tikitacar.roadj.SceneHandoff

/**
 * RoadDJ player 앱의 씬 전환 컨트롤러.
 *
 * - Park / Neutral: RoadDJ player 커스텀 화면
 * - Drive / Reverse / 주행 기어: RoadDJ Media Template 앱
 *
 * Drive 로 넘어갈 때 현재 재생 상태(곡/위치/재생 여부/언어/재생 이력)를
 * SceneHandoff broadcast 로 넘기고, RoadDJ Media Template 앱을 실행합니다.
 */
object SceneController {

    private const val TAG = "RoadDJScene"

    /** 기어 변경이 연속으로 들어올 때 화면이 깜빡이지 않도록 안정화 시간을 둡니다. */
    private const val SETTLE_MS = 1_500L

    /** 기어 정보를 사용할 수 없을 때만 사용하는 속도 기반 폴백 */
    private const val SPEED_DRIVE_KMH = 5f

    /** 상태를 넘겨받은 직후에는 이전 기어 값으로 곧바로 되돌아가지 않도록 잠시 무시합니다. */
    private const val HANDOFF_SUPPRESS_MS = 2_500L

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

    @Volatile
    private var handoffSuppressUntil = 0L

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
        if (!handoffSuppressed()) {
            evaluate(lastGear)
        }
    }

    /** 다른 앱에서 재생 상태를 넘겨받은 직후 호출합니다. */
    fun onHandoffReceived() {
        handoffSuppressUntil = SystemClock.elapsedRealtime() + HANDOFF_SUPPRESS_MS
        pendingScene = null
        handler.removeCallbacks(switchRunnable)
    }

    private fun handoffSuppressed(): Boolean =
        SystemClock.elapsedRealtime() < handoffSuppressUntil

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

        when (target) {
            Scene.PLAYER -> {
                val intent = Intent()
                    .setClassName(SceneHandoff.PLAYER_PACKAGE, SceneHandoff.PLAYER_ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                try {
                    context.startActivity(intent)
                    currentScene = target
                    Log.d(TAG, "씬 전환: $target")
                } catch (e: Exception) {
                    Log.e(TAG, "씬 전환 실패: $target", e)
                }
            }

            Scene.TEMPLATE -> {
                // 현재 상태를 캡처한 뒤 소스 오디오를 멈추고 템플릿 앱으로 넘깁니다.
                val snapshot = PlaybackController.snapshot()
                PlaybackController.pause()
                SceneHandoff.send(
                    context,
                    SceneHandoff.TEMPLATE_PACKAGE,
                    SceneHandoff.TEMPLATE_RECEIVER,
                    snapshot
                )

                val intent = Intent()
                    .setClassName(SceneHandoff.TEMPLATE_PACKAGE, SceneHandoff.TEMPLATE_ACTIVITY)
                    .setAction(SceneHandoff.SHOW_MEDIA_PLAYBACK)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                try {
                    context.startActivity(intent)
                    currentScene = target
                    Log.d(
                        TAG,
                        "씬 전환: $target (${snapshot.file?.substringBeforeLast('.')} " +
                            "@${snapshot.positionMs}ms, playing=${snapshot.playing})"
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "씬 전환 실패: $target", e)
                    if (snapshot.playing) PlaybackController.play()
                    sceneActive = true
                    currentScene = Scene.PLAYER
                }
            }
        }
    }
}
