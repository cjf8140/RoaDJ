package com.tikitacar.roadj.media

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import androidx.car.app.CarContext
import androidx.core.content.ContextCompat
import com.tikitacar.roadj.LocalAudioPlayer
import com.tikitacar.roadj.NearbyPlaceLookup
import com.tikitacar.roadj.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

/**
 * RoadDJ media player 의 헤드리스 데이터 수집기.
 *
 * 화면에 위치/날씨/주변 정보를 표시하지 않고, RoadJ 앱과 동일한 소스에서
 * 데이터를 병행 수신해 RANKING(CarState)만 갱신합니다.
 *
 * - Pleos NaviHelper: 현재 위치 (3초 폴링)
 * - 차량 속도: 75km/h 기준 mood(happy/calm)
 * - OpenWeather: 현재 위치 기준 2분마다 강수/일출·일몰/시간대
 * - Overpass: 최초 위치 + 새 곡 시작 시 주변 판정(place)
 */
object DrivingDataCollector {

    private const val TAG = "RoadDJMediaData"

    private const val NAVI_POLL_INTERVAL_MS = 3_000L
    private const val WEATHER_REFRESH_INTERVAL_MS = 2 * 60 * 1000L
    private const val NEARBY_RADIUS_METERS = 400

    private const val PERMISSION_CAR_SPEED = "android.car.permission.CAR_SPEED"

    private const val OPEN_WEATHER_API_KEY = "YOUR_OPENWEATHER_API_KEY"
    private const val OPEN_WEATHER_CURRENT_URL =
        "https://api.openweathermap.org/data/2.5/weather"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var started = false

    private lateinit var appContext: Context
    private var naviHelper: NaviHelper? = null

    @Volatile
    private var naviConnected = false

    @Volatile
    private var latestLatitude = 0.0

    @Volatile
    private var latestLongitude = 0.0

    private var weatherLoopRunning = false
    private var weatherJob: Job? = null

    private var nearbyJob: Job? = null
    private var nearbyRefreshPending = false
    private var initialNearbyRequested = false

    private var lastNearbyTrackTitle: String? = null

    @Volatile
    private var car: Car? = null

    @Volatile
    private var carPropertyManager: CarPropertyManager? = null

    @Volatile
    private var speedStarted = false

    @Volatile
    private var speedPermissionRequested = false

    /** 프로세스 시작 시 한 번만 호출합니다. (Application.onCreate) */
    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            appContext = context.applicationContext
            started = true

            PlaybackController.initialize(appContext)

            // 새 곡이 시작될 때마다 주변(Overpass) 정보를 갱신합니다.
            PlaybackController.addListener { state -> onPlaybackState(state) }

            initNavigation()

            if (hasSpeedPermission()) {
                startSpeed()
            }
            Log.d(TAG, "데이터 수집 시작 (위치/속도/날씨/주변)")
        }
    }

    private fun onPlaybackState(state: LocalAudioPlayer.State) {
        if (!state.playing) return
        if (state.title == lastNearbyTrackTitle) return
        lastNearbyTrackTitle = state.title
        Log.d(TAG, "새 곡 시작 → 주변 정보 갱신: ${state.title}")
        mainHandler.post { requestNearbyUpdate("새 곡 시작") }
    }

    // ------------------------------------------------------------------
    // NaviHelper: 현재 위치
    // ------------------------------------------------------------------

    private fun initNavigation() {
        try {
            val helper = NaviHelper(appContext)
            naviHelper = helper
            helper.initialize(
                onConnected = {
                    helper.addListener(naviEventListener)
                    naviConnected = true
                    Log.d(TAG, "NaviHelper 연결됨")
                    mainHandler.post(naviPollRunnable)
                },
                onFailed = { throwable ->
                    naviConnected = false
                    Log.w(TAG, "NaviHelper 연결 실패", throwable)
                }
            )
        } catch (e: Exception) {
            naviConnected = false
            Log.e(TAG, "NaviHelper 초기화 실패", e)
        }
    }

    private val naviPollRunnable = object : Runnable {
        override fun run() {
            val helper = naviHelper
            if (helper != null && naviConnected) {
                runCatching { helper.getCurrentLocationInfo() }
                runCatching { helper.getDestinationInfo() }
            }
            mainHandler.postDelayed(this, NAVI_POLL_INTERVAL_MS)
        }
    }

    private val naviEventListener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            val latitude = currentLocationInfo.latitude
            val longitude = currentLocationInfo.longitude
            if (latitude == 0.0 && longitude == 0.0) return

            latestLatitude = latitude
            latestLongitude = longitude
            Log.d(TAG, "현재 위치: $latitude, $longitude (${currentLocationInfo.address})")

            mainHandler.post {
                startWeatherRefreshIfNeeded()
                if (!initialNearbyRequested) {
                    initialNearbyRequested = true
                    requestNearbyUpdate("최초 위치")
                }
            }
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            Log.d(
                TAG,
                "목적지: ${destinationInfo.name} / 남은 거리 ${destinationInfo.remainDistance}m"
            )
        }

        override fun onDestinationChanged() = Unit

        override fun onNavigationStatus(status: NavigationStatus) {
            Log.d(TAG, "내비 상태: $status")
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "NaviHelper 오류: $errorCode")
        }
    }

    // ------------------------------------------------------------------
    // OpenWeather: 2분마다 날씨/강수/일출·일몰/시간대
    // ------------------------------------------------------------------

    private fun startWeatherRefreshIfNeeded() {
        if (weatherLoopRunning) return
        if (latestLatitude == 0.0 && latestLongitude == 0.0) return
        weatherLoopRunning = true
        mainHandler.removeCallbacks(weatherRefreshRunnable)
        mainHandler.post(weatherRefreshRunnable)
        Log.d(TAG, "날씨 2분 주기 갱신 시작")
    }

    private val weatherRefreshRunnable = object : Runnable {
        override fun run() {
            requestWeatherUpdate()
            mainHandler.postDelayed(this, WEATHER_REFRESH_INTERVAL_MS)
        }
    }

    private fun requestWeatherUpdate() {
        val latitude = latestLatitude
        val longitude = latestLongitude
        if (latitude == 0.0 && longitude == 0.0) return
        if (weatherJob?.isActive == true) {
            Log.d(TAG, "날씨 요청 진행 중 → 이번 주기 건너뜀")
            return
        }

        weatherJob = scope.launch {
            val data = withContext(Dispatchers.IO) { fetchWeather(latitude, longitude) } ?: return@launch

            PlaybackController.updateSunTimes(data.sunrise, data.sunset)
            data.utcOffsetSec?.let { offset ->
                PlaybackController.setLocationTimeZone(resolveTimeZone(offset))
            }
            PlaybackController.updateCarState { it.copy(precip = data.precip) }
            Log.d(
                TAG,
                "날씨 갱신(lat=$latitude, lon=$longitude): ${data.summary} / precip=${data.precip}"
            )
        }
    }

    private data class WeatherData(
        val summary: String,
        val precip: String,
        val sunrise: Long,
        val sunset: Long,
        val utcOffsetSec: Int?
    )

    /** blocking 네트워크 요청. Dispatchers.IO 에서만 호출합니다. */
    private fun fetchWeather(latitude: Double, longitude: Double): WeatherData? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(
                "$OPEN_WEATHER_CURRENT_URL?lat=$latitude&lon=$longitude" +
                    "&appid=$OPEN_WEATHER_API_KEY&units=metric&lang=en"
            )
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (responseCode != 200) {
                Log.w(TAG, "OpenWeather 응답 실패: HTTP $responseCode")
                null
            } else {
                val root = JSONObject(body)
                val weather = root.optJSONArray("weather")?.optJSONObject(0)
                val description = weather?.optString("description").orEmpty()
                val temp = root.optJSONObject("main")?.optDouble("temp", 0.0) ?: 0.0
                val (sunrise, sunset) = parseSunEpochs(root)
                WeatherData(
                    summary = String.format(Locale.US, "%s, %.1f°C", description, temp),
                    precip = parsePrecipCode(root),
                    sunrise = sunrise,
                    sunset = sunset,
                    utcOffsetSec = root.optInt("timezone", Int.MIN_VALUE)
                        .takeIf { it != Int.MIN_VALUE }
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "OpenWeather 요청 실패", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parsePrecipCode(root: JSONObject): String {
        val weather = root.optJSONArray("weather")?.optJSONObject(0)
        val main = weather?.optString("main").orEmpty().lowercase(Locale.US)
        val description = weather?.optString("description").orEmpty().lowercase(Locale.US)
        return when {
            main == "snow" || description.contains("snow") || description.contains("sleet") ->
                "snow"

            main == "rain" || main == "drizzle" || main == "thunderstorm" ||
                description.contains("rain") || description.contains("drizzle") ||
                description.contains("thunderstorm") || root.has("rain") ->
                "rain"

            else -> "none"
        }
    }

    private fun parseSunEpochs(root: JSONObject): Pair<Long, Long> {
        val sys = root.optJSONObject("sys")
        return (sys?.optLong("sunrise", 0L) ?: 0L) to (sys?.optLong("sunset", 0L) ?: 0L)
    }

    private fun resolveTimeZone(offsetSeconds: Int): TimeZone {
        val totalMinutes = offsetSeconds / 60
        val sign = if (totalMinutes < 0) "-" else "+"
        val absMinutes = kotlin.math.abs(totalMinutes)
        val id = String.format(Locale.US, "GMT%s%02d:%02d", sign, absMinutes / 60, absMinutes % 60)
        return TimeZone.getTimeZone(id)
    }

    // ------------------------------------------------------------------
    // Overpass: 최초 위치 + 새 곡 시작 시 주변 판정
    // ------------------------------------------------------------------

    private fun requestNearbyUpdate(reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { requestNearbyUpdate(reason) }
            return
        }

        val latitude = latestLatitude
        val longitude = latestLongitude
        if (latitude == 0.0 && longitude == 0.0) return

        if (nearbyJob?.isActive == true) {
            Log.d(TAG, "주변 조회 진행 중 → 완료 후 갱신 예약 ($reason)")
            nearbyRefreshPending = true
            return
        }

        Log.d(TAG, "주변 정보 조회($reason): lat=$latitude lon=$longitude")
        nearbyJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    NearbyPlaceLookup.lookup(latitude, longitude, NEARBY_RADIUS_METERS)
                } catch (e: Exception) {
                    Log.w(TAG, "주변 정보 조회 실패", e)
                    null
                }
            }

            if (result != null) {
                PlaybackController.updateCarState {
                    it.copy(place = placeCodeOf(result.verdict))
                }
                Log.d(TAG, "주변 판정($reason): ${result.verdict} / ${result.reason}")
            }

            nearbyJob = null
            if (nearbyRefreshPending) {
                nearbyRefreshPending = false
                requestNearbyUpdate("대기 중 갱신")
            }
        }
    }

    private fun placeCodeOf(verdict: String): String = when (verdict) {
        "바다" -> "sea"
        "강·물" -> "river"
        "산" -> "mountain"
        "도시" -> "city"
        else -> "none"
    }

    // ------------------------------------------------------------------
    // 차량 속도: mood(happy/calm)
    // ------------------------------------------------------------------

    private fun hasSpeedPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, PERMISSION_CAR_SPEED) ==
            PackageManager.PERMISSION_GRANTED

    /** 호스트에 차량 속도 권한을 요청합니다. (Session 생성 시 호출) */
    fun requestSpeedPermission(carContext: CarContext) {
        if (!started) return
        if (hasSpeedPermission()) {
            startSpeed()
            return
        }
        if (speedPermissionRequested) return
        speedPermissionRequested = true

        try {
            carContext.requestPermissions(listOf(PERMISSION_CAR_SPEED)) { granted, rejected ->
                if (granted.contains(PERMISSION_CAR_SPEED)) {
                    Log.d(TAG, "차량 속도 권한 승인")
                    startSpeed()
                } else {
                    Log.w(TAG, "차량 속도 권한 거부: $rejected")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "차량 속도 권한 요청 실패", e)
        }
    }

    /** 일반 Activity 에서 런타임 권한을 승인받은 뒤 호출합니다. */
    fun onSpeedPermissionGranted() {
        if (!started) return
        if (hasSpeedPermission()) startSpeed()
    }

    private fun startSpeed() {
        if (speedStarted) return
        speedStarted = true

        thread {
            try {
                val connectedCar = Car.createCar(appContext)
                val manager = connectedCar.getCarManager(Car.PROPERTY_SERVICE)
                    as? CarPropertyManager
                car = connectedCar
                carPropertyManager = manager
                if (manager == null) {
                    Log.w(TAG, "CarPropertyManager 없음")
                    return@thread
                }
                manager.registerCallback(
                    speedCallback,
                    VehiclePropertyIds.PERF_VEHICLE_SPEED,
                    CarPropertyManager.SENSOR_RATE_ONCHANGE
                )
                Log.d(TAG, "실시간 속도 수신 시작")
            } catch (e: Exception) {
                Log.w(TAG, "속도 수신 시작 실패", e)
                speedStarted = false
            }
        }
    }

    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.propertyId != VehiclePropertyIds.PERF_VEHICLE_SPEED) return
            val speedInMps = value.value as? Float ?: return
            val speedKmh = speedInMps * 3.6f
            mainHandler.post { PlaybackController.updateSpeed(speedKmh) }
        }

        override fun onErrorEvent(propId: Int, zone: Int) {
            if (propId != VehiclePropertyIds.PERF_VEHICLE_SPEED) return
            mainHandler.post { PlaybackController.updateSpeed(0f) }
        }
    }
}
