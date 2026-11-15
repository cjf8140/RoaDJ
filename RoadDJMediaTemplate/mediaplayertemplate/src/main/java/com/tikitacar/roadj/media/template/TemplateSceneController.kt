package com.tikitacar.roadj.media.template

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
 * RoadDJ Media Template 앱의 씬 전환 컨트롤러.
 *
 * 자동 기어 복귀는 **RoadDJ player 의 Drive handoff 로 진입한 경우에만** 동작합니다.
 * 사용자가 템플릿 앱을 직접 실행한 경우에는 기어가 Park 여도 player 로 되돌리지 않습니다.
 *
 * - handoff 로 진입: 실제 Drive 기어를 한 번 확인한 뒤 Park/Neutral 이 되면 player 로 복귀
 * - 직접 실행: 현재 화면을 유지
 */
object TemplateSceneController {

    private const val TAG = "RoadDJTemplateScene"

    private const val SETTLE_MS = 1_500L

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var sceneActive = false

    private var lastGear: Int? = null
    private var pendingPlayer = false
    private var requestPending = false

    @Volatile
    private var lastHandoffAt = 0L

    /** RoadDJ player 의 handoff 로 진입했는지 여부 */
    private var handoffMode = false

    /** handoff 이후 실제 Drive 기어를 확인했는지 여부 */
    private var armed = false

    private val switchRunnable = Runnable {
        if (!pendingPlayer) return@Runnable
        pendingPlayer = false
        if (sceneActive && handoffMode) {
            switchToPlayer()
        }
    }

    fun initialize(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            Log.d(TAG, "TemplateSceneController 초기화")
        }
    }

    fun markSceneVisible() {
        sceneActive = true
        Log.d(TAG, "템플릿 씬 활성화 (handoff=$handoffMode, armed=$armed)")

        if (!handoffMode) {
            // 직접 실행한 경우에는 기어로 자동 복귀하지 않습니다.
            cancelPending()
            return
        }
        if (armed) {
            evaluate(lastGear)
        } else {
            cancelPending()
        }
    }

    fun deactivate() {
        sceneActive = false
        pendingPlayer = false
        handler.removeCallbacks(switchRunnable)
        Log.d(TAG, "템플릿 씬 비활성화")
    }

    /**
     * 수동으로 템플릿 앱을 연 경우 RoadDJ player 에 현재 재생 상태를 요청합니다.
     * 자동 handoff 로 진입한 경우에는 요청하지 않습니다.
     */
    fun requestPlayerStateIfNeeded(context: Context) {
        if (requestPending) return
        if (SystemClock.elapsedRealtime() - lastHandoffAt < 2_000L) {
            Log.d(TAG, "상태 요청 생략 (최근 handoff 수신)")
            return
        }

        requestPending = true
        handler.postDelayed({
            requestPending = false
            if (!sceneActive) {
                Log.d(TAG, "상태 요청 취소 (씬 비활성)")
                return@postDelayed
            }
            if (SystemClock.elapsedRealtime() - lastHandoffAt < 2_000L) {
                Log.d(TAG, "상태 요청 취소 (handoff 수신)")
                return@postDelayed
            }
            Log.d(TAG, "수동 실행/복귀 → RoadDJ player 상태 요청")
            SceneHandoff.request(
                context,
                SceneHandoff.PLAYER_PACKAGE,
                SceneHandoff.PLAYER_STATE_REQUEST_RECEIVER
            )
        }, 800L)
    }

    /** RoadDJ player 앱에서 재생 상태를 넘겨받았을 때 호출합니다. */
    fun onHandoffReceived() {
        handoffMode = true
        armed = matchesDriveGear(lastGear)
        lastHandoffAt = SystemClock.elapsedRealtime()
        cancelPending()
        Log.d(TAG, "handoff 수신 (lastGear=$lastGear, armed=$armed)")
    }

    fun onGearChanged(gear: Int) {
        lastGear = gear

        if (!handoffMode) {
            // 직접 실행 모드: 기어 변경으로 player 로 자동 전환하지 않습니다.
            return
        }

        if (!armed) {
            if (matchesDriveGear(gear)) {
                armed = true
                Log.d(TAG, "handoff 모드 armed (Drive 기어 확인: $gear)")
            } else {
                Log.d(TAG, "handoff 모드 대기 (현재 기어=$gear)")
                return
            }
        }

        if (!sceneActive) return
        evaluate(gear)
    }

    private fun evaluate(gear: Int?) {
        when (gear) {
            VehicleGear.GEAR_PARK,
            VehicleGear.GEAR_NEUTRAL -> schedulePlayer()

            VehicleGear.GEAR_DRIVE,
            VehicleGear.GEAR_REVERSE,
            in VehicleGear.GEAR_FIRST..VehicleGear.GEAR_NINTH -> cancelPending()

            else -> Unit
        }
    }

    private fun matchesDriveGear(gear: Int?): Boolean = when (gear) {
        VehicleGear.GEAR_DRIVE,
        VehicleGear.GEAR_REVERSE -> true

        in VehicleGear.GEAR_FIRST..VehicleGear.GEAR_NINTH -> true

        else -> false
    }

    private fun schedulePlayer() {
        if (pendingPlayer) return
        pendingPlayer = true
        handler.removeCallbacks(switchRunnable)
        handler.postDelayed(switchRunnable, SETTLE_MS)
        Log.d(TAG, "Park/Neutral 감지 → RoadDJ player 전환 예약")
    }

    private fun cancelPending() {
        if (!pendingPlayer) return
        pendingPlayer = false
        handler.removeCallbacks(switchRunnable)
    }

    private fun switchToPlayer() {
        val context = appContext ?: return
        sceneActive = false

        val snapshot = PlaybackController.snapshot()
        PlaybackController.pause()
        SceneHandoff.send(
            context,
            SceneHandoff.PLAYER_PACKAGE,
            SceneHandoff.PLAYER_RECEIVER,
            snapshot
        )

        val intent = Intent()
            .setClassName(SceneHandoff.PLAYER_PACKAGE, SceneHandoff.PLAYER_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        try {
            context.startActivity(intent)
            handoffMode = false
            armed = false
            Log.d(
                TAG,
                "RoadDJ player 로 전환: ${snapshot.file?.substringBeforeLast('.')} " +
                    "@${snapshot.positionMs}ms, playing=${snapshot.playing}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "RoadDJ player 전환 실패", e)
            if (snapshot.playing) PlaybackController.play()
            sceneActive = true
        }
    }
}
