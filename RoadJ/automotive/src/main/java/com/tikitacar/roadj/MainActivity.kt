package com.tikitacar.roadj

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RoadJMain"
        private const val REQUEST_CAR_SPEED = 1001
        private const val PERMISSION_CAR_SPEED = "android.car.permission.CAR_SPEED"
        private const val NAVI_POLL_INTERVAL_MS = 3_000L
        private const val CLOCK_UPDATE_INTERVAL_MS = 1_000L
        /** 실시간 현재 위치 기준 날씨 갱신 주기 (2분) */
        private const val WEATHER_REFRESH_INTERVAL_MS = 2 * 60 * 1000L
        private const val NEARBY_RADIUS_METERS = 400

        // OpenWeather 현재 날씨 API
        private const val OPEN_WEATHER_API_KEY = "YOUR_OPENWEATHER_API_KEY"
        private const val OPEN_WEATHER_CURRENT_URL = "https://api.openweathermap.org/data/2.5/weather"
    }

    private lateinit var speedTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var fetchSpeedButton: Button
    private lateinit var testInternetButton: Button
    private lateinit var naviStatusTextView: TextView
    private lateinit var currentLocationTextView: TextView
    private lateinit var destinationTextView: TextView
    private lateinit var weatherTextView: TextView
    private lateinit var weatherUpdatedTextView: TextView
    private lateinit var monthSeasonTextView: TextView
    private lateinit var sunTimesTextView: TextView
    private lateinit var nearbyTextView: TextView
    private lateinit var nearbyUpdatedTextView: TextView
    private lateinit var trackTitleTextView: TextView
    private lateinit var trackIndexTextView: TextView
    private lateinit var rankingStateTextView: TextView
    private lateinit var languageSpinner: Spinner
    private lateinit var playPauseButton: Button
    private lateinit var prevButton: Button
    private lateinit var nextButton: Button

    /** PlaybackController 에 등록한 재생 상태 리스너 해제 함수 */
    private var removePlayerListener: (() -> Unit)? = null

    /** 최신 현재 위치 (2분 주기 날씨 조회 기준) */
    @Volatile
    private var latestLatitude = 0.0

    @Volatile
    private var latestLongitude = 0.0

    /** 2분 주기 날씨 갱신 루프 실행 여부 */
    @Volatile
    private var weatherLoopRunning = false

    /** API 요청을 UI 스레드 밖에서 실행하는 코루틴 스코프 (메인 디스패처) */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 진행 중인 날씨/주변 정보 요청 */
    private var weatherJob: Job? = null
    private var nearbyJob: Job? = null

    /** 주변 조회 중 새 요청이 들어오면 완료 후 한 번 더 갱신하기 위한 플래그 */
    private var nearbyRefreshPending = false

    /** 마지막 성공 업데이트 시각 (epoch millis, 0 = 아직 없음) */
    @Volatile
    private var lastWeatherUpdatedAt = 0L

    @Volatile
    private var lastNearbyUpdatedAt = 0L

    /** 마지막으로 주변 정보를 갱신한 곡 (새 곡 시작 감지용) */
    private var lastNearbyTrackTitle: String? = null

    /** 최초 로딩 문구를 한 번만 표시하기 위한 플래그 */
    private var weatherLoadedOnce = false

    /** 주변 정보 최초 로딩 문구를 한 번만 표시하기 위한 플래그 */
    private var nearbyLoadedOnce = false

    /** 현재 위치(날씨 응답) 기준 시간대. 날씨 수신 전에는 기기 시간대를 사용합니다. */
    @Volatile
    private var locationTimeZone: TimeZone = TimeZone.getDefault()

    /** 현재 위치의 UTC 오프셋(초). null 이면 아직 위치 시간대를 모름. */
    @Volatile
    private var locationUtcOffsetSec: Int? = null

    /** 현재 시간 표시용 AM/PM 포맷 (메인 스레드에서만 사용) */
    private val clockTimeFormat = SimpleDateFormat("h:mm:ss a", Locale.US)

    /** 위치를 처음 받았을 때 주변 정보를 1회만 조회하기 위한 가드 */
    private val nearbyRequestStarted = AtomicBoolean(false)

    /** Pleos Connect NaviHelper (현재 위치/목적지 조회) */
    @Volatile
    private var naviHelper: NaviHelper? = null

    @Volatile
    private var naviConnected = false

    private val uiHandler = Handler(Looper.getMainLooper())

    private val naviPollRunnable = object : Runnable {
        override fun run() {
            requestNavigationInfo()
            uiHandler.postDelayed(this, NAVI_POLL_INTERVAL_MS)
        }
    }

    /** 시스템 시계 기준 현재 시간/월/계절을 1초마다 갱신 */
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateMonthAndSeason()
            uiHandler.postDelayed(this, CLOCK_UPDATE_INTERVAL_MS)
        }
    }

    /** 최신 현재 위치(위도/경도) 기준으로 2분마다 날씨를 갱신 */
    private val weatherRefreshRunnable = object : Runnable {
        override fun run() {
            requestWeatherUpdate()
            uiHandler.postDelayed(this, WEATHER_REFRESH_INTERVAL_MS)
        }
    }

    private val naviEventListener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            val text = buildString {
                append("현재 위치: ")
                append(if (currentLocationInfo.address.isBlank()) "주소 정보 없음" else currentLocationInfo.address)
                append(
                    String.format(
                        Locale.getDefault(),
                        "\n  위도 %.6f, 경도 %.6f, 방향 %.0f°",
                        currentLocationInfo.latitude,
                        currentLocationInfo.longitude,
                        currentLocationInfo.heading
                    )
                )
            }
            runOnUiThread { currentLocationTextView.text = text }

            // 실시간 현재 위치를 날씨 조회 기준으로 기록 (0,0 은 위치 미확인으로 간주)
            if (currentLocationInfo.latitude != 0.0 || currentLocationInfo.longitude != 0.0) {
                latestLatitude = currentLocationInfo.latitude
                latestLongitude = currentLocationInfo.longitude
                Log.d(
                    TAG,
                    "위치 수신: ${currentLocationInfo.latitude}, ${currentLocationInfo.longitude}"
                )
            }

            // 위치가 확인되면 2분 주기 날씨 갱신 시작 (첫 조회는 즉시)
            startWeatherRefreshIfNeeded()

            // 최초 위치에 대해서만 주변 정보를 조회 (이후에는 새 곡 시작 시 갱신)
            if (nearbyRequestStarted.compareAndSet(false, true)) {
                requestNearbyUpdate("최초 위치")
            }
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            val text = buildString {
                append("현재 목적지: ")
                append(if (destinationInfo.name.isBlank()) "(이름 없음)" else destinationInfo.name)
                if (destinationInfo.address.isNotBlank()) {
                    append("\n  ").append(destinationInfo.address)
                }
                append("\n  남은 거리 ").append(formatDistance(destinationInfo.remainDistance))
                append(" · 남은 시간 ").append(formatDuration(destinationInfo.remainTime))
            }
            runOnUiThread { destinationTextView.text = text }
        }

        override fun onDestinationChanged() {
            requestNavigationInfo()
        }

        override fun onNavigationStatus(status: NavigationStatus) {
            runOnUiThread {
                naviStatusTextView.text = "내비: " + when (status) {
                    NavigationStatus.INACTIVE -> "비활성"
                    NavigationStatus.INITIALIZING -> "초기화 중"
                    NavigationStatus.FREE_DRIVE -> "자유 주행 (목적지 없음)"
                    NavigationStatus.ROUTE_DRIVE -> "경로 안내 중"
                }
                if (status != NavigationStatus.ROUTE_DRIVE) {
                    destinationTextView.text = "현재 목적지: 설정된 목적지 없음"
                }
            }
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "NaviHelper onError: $errorCode")
            runOnUiThread { naviStatusTextView.text = "내비 오류: $errorCode" }
        }
    }

    @Volatile
    private var car: Car? = null

    @Volatile
    private var carPropertyManager: CarPropertyManager? = null

    @Volatile
    private var carConnecting = false

    @Volatile
    private var realtimeEnabled = false

    @Volatile
    private var pendingStartRealtime = false

    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.propertyId != VehiclePropertyIds.PERF_VEHICLE_SPEED) return
            val speedInMps = (value.value as? Float) ?: return
            val speedInKmh = speedInMps * 3.6f
            runOnUiThread {
                // 75km/h 이상이면 RANKING mood=happy, 미만이면 calm
                PlaybackController.updateSpeed(speedInKmh)
                if (realtimeEnabled) {
                    speedTextView.text = String.format(Locale.getDefault(), "%.1f km/h", speedInKmh)
                }
            }
        }

        override fun onErrorEvent(propId: Int, zone: Int) {
            if (propId != VehiclePropertyIds.PERF_VEHICLE_SPEED) return
            runOnUiThread {
                if (realtimeEnabled) {
                    speedTextView.text = "속도 측정 실패"
                }
                PlaybackController.updateSpeed(0f)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        speedTextView = findViewById(R.id.tv_speed)
        statusTextView = findViewById(R.id.tv_status)
        fetchSpeedButton = findViewById(R.id.btn_get_speed)
        testInternetButton = findViewById(R.id.btn_test_internet)
        naviStatusTextView = findViewById(R.id.tv_navi_status)
        currentLocationTextView = findViewById(R.id.tv_current_location)
        destinationTextView = findViewById(R.id.tv_destination)
        weatherTextView = findViewById(R.id.tv_weather)
        weatherUpdatedTextView = findViewById(R.id.tv_weather_updated)
        monthSeasonTextView = findViewById(R.id.tv_month_season)
        sunTimesTextView = findViewById(R.id.tv_sun_times)
        nearbyTextView = findViewById(R.id.tv_nearby)
        nearbyUpdatedTextView = findViewById(R.id.tv_nearby_updated)
        trackTitleTextView = findViewById(R.id.tv_track_title)
        trackIndexTextView = findViewById(R.id.tv_track_index)
        rankingStateTextView = findViewById(R.id.tv_ranking_state)
        languageSpinner = findViewById(R.id.spinner_lang)
        playPauseButton = findViewById(R.id.btn_play_pause)
        prevButton = findViewById(R.id.btn_prev)
        nextButton = findViewById(R.id.btn_next)

        // 5. assets/audio 로컬 음악 재생 (PlaybackController 가 재생/순위를 관리)
        PlaybackController.initialize(this)
        removePlayerListener = PlaybackController.addListener { state ->
            runOnUiThread {
                renderPlayerState(state)
                // 새로운 곡이 재생되면 주변 정보(Overpass)를 갱신합니다.
                maybeRefreshNearbyForNewTrack(state)
            }
        }
        renderPlayerState(PlaybackController.state())
        setupLanguageSelector()
        playPauseButton.setOnClickListener { PlaybackController.toggle() }
        prevButton.setOnClickListener { PlaybackController.previous() }
        nextButton.setOnClickListener { PlaybackController.next() }

        // 시스템 시계 기준 현재 시간/월/계절 표시
        startClock()

        // 1. 차량 SDK 연결 초기화
        initVehicleSdk()

        // 2. Pleos Connect NaviHelper 초기화 (현재 위치 / 현재 목적지)
        initNaviSdk()

        // 3. 실시간 속도 토글 버튼 이벤트
        fetchSpeedButton.setOnClickListener {
            toggleRealtimeSpeed()
        }

        // 4. 인터넷 연결 테스트 버튼 이벤트
        testInternetButton.setOnClickListener {
            testInternetConnection()
        }
    }

    private fun initVehicleSdk() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            Toast.makeText(this, "차량 에뮬레이터 또는 Pleos 환경이 아닙니다.", Toast.LENGTH_SHORT).show()
            return
        }

        // Car.createCar()는 CarService 바인딩을 기다리므로 메인 스레드에서 호출하지 않는다.
        carConnecting = true
        statusTextView.text = "차량 서비스 연결 중..."

        thread {
            var connectedCar: Car? = null
            var connectedManager: CarPropertyManager? = null
            try {
                connectedCar = Car.createCar(applicationContext)
                connectedManager = connectedCar
                    .getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            } catch (e: Exception) {
                connectedManager = null
            }

            car = connectedCar
            carPropertyManager = connectedManager
            carConnecting = false

            runOnUiThread {
                if (connectedManager != null) {
                    statusTextView.text = "차량 서비스 연결됨"
                    if (pendingStartRealtime) {
                        pendingStartRealtime = false
                        startRealtimeSpeed()
                    }
                } else {
                    statusTextView.text = "Vehicle Service 연결 불가"
                    if (pendingStartRealtime) {
                        pendingStartRealtime = false
                        realtimeEnabled = false
                        speedTextView.text = ""
                        speedTextView.visibility = TextView.GONE
                        updateSpeedButton()
                    }
                }
            }
        }
    }

    private fun initNaviSdk() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            naviStatusTextView.text = "내비: 차량 환경 아님"
            return
        }

        try {
            val helper = NaviHelper(applicationContext)
            naviHelper = helper
            naviStatusTextView.text = "내비: 연결 중..."

            helper.initialize(
                onConnected = {
                    helper.addListener(naviEventListener)
                    runOnUiThread {
                        naviConnected = true
                        naviStatusTextView.text = "내비: 연결됨"
                        startNaviPolling()
                    }
                },
                onFailed = { throwable ->
                    Log.w(TAG, "NaviHelper 연결 실패", throwable)
                    naviConnected = false
                    runOnUiThread { naviStatusTextView.text = "내비: 연결 실패" }
                }
            )
        } catch (e: Exception) {
            naviConnected = false
            Log.e(TAG, "NaviHelper 초기화 실패", e)
            naviStatusTextView.text = "내비: 초기화 실패"
        }
    }

    private fun startNaviPolling() {
        uiHandler.removeCallbacks(naviPollRunnable)
        uiHandler.post(naviPollRunnable)
    }

    private fun stopNaviPolling() {
        uiHandler.removeCallbacks(naviPollRunnable)
    }

    /** 조회 API는 값을 직접 반환하지 않으므로 결과는 NaviHelperEventListener로 전달됩니다. */
    private fun requestNavigationInfo() {
        val helper = naviHelper
        if (helper == null || !naviConnected) return
        try {
            helper.getCurrentLocationInfo()
            helper.getDestinationInfo()
        } catch (e: Exception) {
            Log.w(TAG, "내비 정보 요청 실패", e)
        }
    }

    private fun formatDistance(meters: Int): String {
        return if (meters >= 1000) {
            String.format(Locale.getDefault(), "%.1f km", meters / 1000.0)
        } else {
            "${meters.coerceAtLeast(0)} m"
        }
    }

    private fun formatDuration(seconds: Int): String {
        val totalMinutes = seconds.coerceAtLeast(0) / 60
        return if (totalMinutes >= 60) {
            "${totalMinutes / 60}시간 ${totalMinutes % 60}분"
        } else {
            "${totalMinutes}분"
        }
    }

    /** 위치가 확인되면 2분 주기 날씨 갱신을 시작합니다. (이미 실행 중이면 유지) */
    private fun startWeatherRefreshIfNeeded() {
        if (weatherLoopRunning) return
        if (latestLatitude == 0.0 && latestLongitude == 0.0) return
        weatherLoopRunning = true
        uiHandler.removeCallbacks(weatherRefreshRunnable)
        uiHandler.post(weatherRefreshRunnable)
        Log.d(TAG, "날씨 2분 주기 갱신 시작: $latestLatitude, $latestLongitude")
    }

    /** 액티비티가 화면에서 벗어나면 날씨 갱신 루프를 중지합니다. */
    private fun stopWeatherRefresh() {
        weatherLoopRunning = false
        uiHandler.removeCallbacks(weatherRefreshRunnable)
    }

    /** OpenWeather 응답을 파싱한 결과 (UI 스레드로 전달) */
    private data class WeatherSnapshot(
        val text: String,
        val sunText: String,
        val precip: String,
        val sunrise: Long,
        val sunset: Long,
        val utcOffsetSec: Int?
    )

    /**
     * 최신 현재 위치(위도/경도)로 OpenWeather 현재 날씨를 조회합니다.
     * 네트워크 요청은 Dispatchers.IO 에서 실행되어 UI/재생을 막지 않습니다.
     */
    private fun requestWeatherUpdate() {
        val latitude = latestLatitude
        val longitude = latestLongitude
        if (latitude == 0.0 && longitude == 0.0) return
        if (weatherJob?.isActive == true) {
            Log.d(TAG, "날씨 요청 진행 중 → 이번 주기 건너뜀")
            return
        }

        if (!weatherLoadedOnce) {
            weatherTextView.text = "날씨: 불러오는 중..."
            sunTimesTextView.text = "일출/일몰: 불러오는 중..."
        }

        Log.d(TAG, "날씨 조회: lat=$latitude lon=$longitude")
        weatherJob = ioScope.launch {
            val snapshot = withContext(Dispatchers.IO) { fetchWeather(latitude, longitude) }
            if (snapshot != null) {
                weatherTextView.text = snapshot.text
                sunTimesTextView.text = snapshot.sunText
                weatherLoadedOnce = true

                if (snapshot.sunrise > 0L && snapshot.sunset > 0L) {
                    PlaybackController.updateSunTimes(snapshot.sunrise, snapshot.sunset)
                }
                snapshot.utcOffsetSec?.let { offset ->
                    locationUtcOffsetSec = offset
                    locationTimeZone = resolveTimeZone(offset)
                    PlaybackController.setLocationTimeZone(locationTimeZone)
                }
                PlaybackController.updateCarState { it.copy(precip = snapshot.precip) }
                updateMonthAndSeason()

                lastWeatherUpdatedAt = System.currentTimeMillis()
                updateWeatherUpdatedLabel()
            } else if (!weatherLoadedOnce) {
                weatherTextView.text = "날씨: 불러오기 실패"
                sunTimesTextView.text = "일출/일몰: --"
            }
        }
    }

    /** 실제 HTTP 요청/파싱 (blocking). 반드시 Dispatchers.IO 에서 호출합니다. */
    private fun fetchWeather(latitude: Double, longitude: Double): WeatherSnapshot? {
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
            Log.d(TAG, "OpenWeather HTTP $responseCode (lat=$latitude, lon=$longitude): $body")

            if (responseCode != 200) {
                Log.w(TAG, "OpenWeather 응답 실패: HTTP $responseCode")
                null
            } else {
                val (sunrise, sunset) = parseSunEpochs(body)
                WeatherSnapshot(
                    text = parseCurrentWeather(body),
                    sunText = parseSunTimes(body),
                    precip = parsePrecipCode(body),
                    sunrise = sunrise,
                    sunset = sunset,
                    utcOffsetSec = JSONObject(body)
                        .optInt("timezone", Int.MIN_VALUE)
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

    private fun updateWeatherUpdatedLabel() {
        if (lastWeatherUpdatedAt <= 0L) return
        weatherUpdatedTextView.text =
            "마지막 업데이트: ${formatUpdatedAt(lastWeatherUpdatedAt)}"
    }

    /** 마지막 업데이트 시각을 현재 위치 시간대의 AM/PM 으로 표시합니다. */
    private fun formatUpdatedAt(epochMillis: Long): String {
        val formatter = SimpleDateFormat("h:mm:ss a", Locale.US).apply {
            timeZone = locationTimeZone
        }
        return formatter.format(Date(epochMillis))
    }

    private fun parseCurrentWeather(json: String): String {
        val root = JSONObject(json)
        val main = root.getJSONObject("main")
        val temp = main.getDouble("temp")
        val feelsLike = main.optDouble("feels_like", temp)
        val humidity = main.optInt("humidity", -1)

        val weather = root.optJSONArray("weather")?.optJSONObject(0)
        val description = weather?.optString("description").orEmpty()
        val windSpeed = root.optJSONObject("wind")?.optDouble("speed", 0.0) ?: 0.0

        return buildString {
            append("날씨: ")
            append(if (description.isBlank()) "-" else description)
            append(String.format(Locale.getDefault(), ", %.1f°C (체감 %.1f°C)", temp, feelsLike))
            if (humidity >= 0) append(", 습도 ").append(humidity).append("%")
            append(String.format(Locale.getDefault(), ", 바람 %.1f m/s", windSpeed))
        }
    }

    /** OpenWeather 응답의 sys.sunrise / sys.sunset 을 해당 위치 시간대로 표시합니다. */
    private fun parseSunTimes(json: String): String {
        return try {
            val root = JSONObject(json)
            val sys = root.optJSONObject("sys")
            val sunrise = sys?.optLong("sunrise", 0L) ?: 0L
            val sunset = sys?.optLong("sunset", 0L) ?: 0L
            if (sunrise <= 0L || sunset <= 0L) {
                "일출/일몰: --"
            } else {
                val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
                formatter.timeZone = resolveTimeZone(root.optInt("timezone", Int.MIN_VALUE))
                val sunriseText = formatter.format(Date(sunrise * 1000L))
                val sunsetText = formatter.format(Date(sunset * 1000L))
                "일출 $sunriseText · 일몰 $sunsetText"
            }
        } catch (e: Exception) {
            Log.w(TAG, "일출/일몰 파싱 실패", e)
            "일출/일몰: --"
        }
    }

    /** OpenWeather의 현재 날씨를 RANKING 의 precip 값(none/rain/snow)으로 변환합니다. */
    private fun parsePrecipCode(json: String): String {
        return try {
            val root = JSONObject(json)
            val weather = root.optJSONArray("weather")?.optJSONObject(0)
            val main = weather?.optString("main").orEmpty().lowercase(Locale.US)
            val description = weather?.optString("description").orEmpty().lowercase(Locale.US)
            when {
                main == "snow" || description.contains("snow") || description.contains("sleet") ->
                    "snow"

                main == "rain" || main == "drizzle" || main == "thunderstorm" ||
                    description.contains("rain") || description.contains("drizzle") ||
                    description.contains("thunderstorm") || root.has("rain") ->
                    "rain"

                else -> "none"
            }
        } catch (e: Exception) {
            Log.w(TAG, "강수 파싱 실패", e)
            "none"
        }
    }

    /** 일출/일몰 epoch(초)를 읽습니다. 낮/밤 판정에 사용합니다. */
    private fun parseSunEpochs(json: String): Pair<Long, Long> {
        return try {
            val sys = JSONObject(json).optJSONObject("sys")
            (sys?.optLong("sunrise", 0L) ?: 0L) to (sys?.optLong("sunset", 0L) ?: 0L)
        } catch (e: Exception) {
            Log.w(TAG, "일출/일몰 epoch 파싱 실패", e)
            0L to 0L
        }
    }

    /** OpenWeather의 timezone(UTC 오프셋 초) 값을 TimeZone으로 변환합니다. */
    private fun resolveTimeZone(offsetSeconds: Int): TimeZone {
        if (offsetSeconds == Int.MIN_VALUE) return TimeZone.getDefault()
        val totalMinutes = offsetSeconds / 60
        val sign = if (totalMinutes < 0) "-" else "+"
        val absMinutes = kotlin.math.abs(totalMinutes)
        val id = String.format(Locale.US, "GMT%s%02d:%02d", sign, absMinutes / 60, absMinutes % 60)
        return TimeZone.getTimeZone(id)
    }

    /**
     * 현재 위치 주변 정보를 조회합니다. (Overpass is_in/around + Nominatim + Photon 대체)
     * 네트워크 요청은 Dispatchers.IO 에서 실행되어 UI/재생을 막지 않습니다.
     * 이미 조회 중이면 완료 후 한 번 더 갱신합니다.
     */
    private fun requestNearbyUpdate(reason: String) {
        // 위치 콜백(백그라운드)에서 호출될 수 있으므로 메인 스레드로 맞춥니다.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            uiHandler.post { requestNearbyUpdate(reason) }
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

        if (!nearbyLoadedOnce) {
            nearbyTextView.text = "주변 정보: 조회 중..."
        }

        Log.d(TAG, "주변 정보 조회($reason): lat=$latitude lon=$longitude")
        nearbyJob = ioScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    NearbyPlaceLookup.lookup(latitude, longitude, NEARBY_RADIUS_METERS)
                } catch (e: Exception) {
                    Log.w(TAG, "주변 정보 조회 실패", e)
                    null
                }
            }

            if (result != null) {
                nearbyTextView.text = formatNearbyResult(result)
                nearbyLoadedOnce = true
                Log.d(TAG, "주변 판정($reason): ${result.verdict} / ${result.reason}")
                // RANKING 규칙: 주변 장소(바다/강·물/산/도시)가 곡 순위의 place 값입니다.
                PlaybackController.updateCarState { it.copy(place = placeCodeOf(result.verdict)) }
                lastNearbyUpdatedAt = System.currentTimeMillis()
                updateNearbyUpdatedLabel()
            } else if (!nearbyLoadedOnce) {
                nearbyTextView.text = "주변 정보: 조회 실패"
            }

            // 현재 조회가 끝났으므로 대기 중인 요청이 있으면 이어서 갱신합니다.
            nearbyJob = null
            if (nearbyRefreshPending) {
                nearbyRefreshPending = false
                requestNearbyUpdate("대기 중 갱신")
            }
        }
    }

    /** 새로운 곡이 재생되면 주변 정보를 갱신합니다. (같은 곡의 일시정지/재개는 무시) */
    private fun maybeRefreshNearbyForNewTrack(state: LocalAudioPlayer.State) {
        if (!state.playing) return
        if (state.title == lastNearbyTrackTitle) return
        lastNearbyTrackTitle = state.title
        Log.d(TAG, "새 곡 시작 → 주변 정보 갱신: ${state.title}")
        requestNearbyUpdate("새 곡 시작")
    }

    private fun updateNearbyUpdatedLabel() {
        if (lastNearbyUpdatedAt <= 0L) return
        nearbyUpdatedTextView.text =
            "마지막 업데이트: ${formatUpdatedAt(lastNearbyUpdatedAt)}"
    }

    private fun formatNearbyResult(result: NearbyPlaceLookup.Result): String = buildString {
        append("주변 정보: ").append(result.verdict)
        append("\n  ")
        append(result.categories.joinToString(" · ") { "${it.label} ${it.count}" })

        val top = result.categories.firstOrNull { it.label == result.verdict }
        if (top != null && top.sampleNames.isNotEmpty()) {
            append("\n  ").append(result.verdict).append(": ")
            append(top.sampleNames.joinToString(" / "))
        }
        if (result.displayName.isNotBlank()) {
            append("\n  ").append(result.displayName)
        }
        append("\n  근거(").append(result.source).append("): ").append(result.reason)
    }

    /** 주변 판정 결과(한국어 라벨)를 RANKING 의 place 값으로 변환합니다. */
    private fun placeCodeOf(verdict: String): String = when (verdict) {
        "바다" -> "sea"
        "강·물" -> "river"
        "산" -> "mountain"
        "도시" -> "city"
        else -> "none"
    }

    /** 로컬 플레이어 상태를 화면에 반영합니다. */
    private fun renderPlayerState(state: LocalAudioPlayer.State) {
        trackTitleTextView.text = state.title
        trackIndexTextView.text =
            if (state.total == 0) "곡 없음" else "${state.position} / ${state.total}"
        playPauseButton.text = if (state.playing) "일시정지" else "재생"
        updateRankingLabel()
    }

    /** RANKING 곡 언어(ko/en/ja) 선택 UI 를 구성합니다. */
    private fun setupLanguageSelector() {
        val labels = listOf("한국어", "English", "日本語")
        val codes = listOf("ko", "en", "ja")

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        languageSpinner.adapter = adapter

        val currentIndex = codes.indexOf(PlaybackController.carState().lang).coerceAtLeast(0)
        languageSpinner.setSelection(currentIndex, false)
        languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                PlaybackController.setLanguage(codes[position])
                updateRankingLabel()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /** RANKING 에 사용 중인 lang/mood 를 표시합니다. */
    private fun updateRankingLabel() {
        val state = PlaybackController.carState()
        val langLabel = when (state.lang) {
            "en" -> "English"
            "ja" -> "日本語"
            else -> "한국어"
        }
        rankingStateTextView.text = "곡 언어: $langLabel · mood: ${state.mood}"
    }

    /**
     * 현재 위치(날씨 응답의 timezone) 기준 시간을 AM/PM 형식으로 표시합니다.
     * 위치 시간대를 아직 모르면 기기 시간대로 표시합니다.
     */
    private fun updateMonthAndSeason() {
        val now = Calendar.getInstance(locationTimeZone)
        // Calendar.MONTH는 0부터 시작하므로 +1
        val month = now.get(Calendar.MONTH) + 1
        val season = when (month) {
            3, 4, 5 -> "봄"
            6, 7, 8 -> "여름"
            9, 10, 11 -> "가을"
            else -> "겨울"
        }
        // SimpleDateFormat 은 자체 timeZone 을 사용하므로 위치 시간대로 맞춰 줍니다.
        clockTimeFormat.timeZone = locationTimeZone
        val time = clockTimeFormat.format(now.time)
        val zoneLabel = locationUtcOffsetSec
            ?.let { "현지 시간 ${formatUtcOffset(it)}" }
            ?: "기기 시간"
        monthSeasonTextView.text =
            "현재 시간: $time ($zoneLabel) · 현재 월: ${month}월 · 계절: $season"

        // 날짜/시간대/계절이 바뀌면 RANKING 순위에 반영합니다.
        PlaybackController.refreshClockState()
    }

    /** UTC 오프셋(초)을 'UTC+9' / 'UTC+5:30' 형태로 표시합니다. */
    private fun formatUtcOffset(offsetSeconds: Int): String {
        val totalMinutes = offsetSeconds / 60
        val sign = if (totalMinutes < 0) "-" else "+"
        val absMinutes = kotlin.math.abs(totalMinutes)
        return if (absMinutes % 60 == 0) {
            "UTC$sign${absMinutes / 60}"
        } else {
            String.format(Locale.US, "UTC%s%d:%02d", sign, absMinutes / 60, absMinutes % 60)
        }
    }

    private fun startClock() {
        uiHandler.removeCallbacks(clockRunnable)
        uiHandler.post(clockRunnable)
    }

    private fun stopClock() {
        uiHandler.removeCallbacks(clockRunnable)
    }

    private fun toggleRealtimeSpeed() {
        if (realtimeEnabled) {
            stopRealtimeSpeed()
        } else {
            startRealtimeSpeed()
        }
    }

    private fun startRealtimeSpeed() {
        val manager = carPropertyManager
        if (manager == null) {
            speedTextView.visibility = TextView.VISIBLE
            speedTextView.text = if (carConnecting) "차량 서비스 연결 중..." else "Vehicle Service 연결 불가"
            if (carConnecting) {
                pendingStartRealtime = true
                realtimeEnabled = true
                updateSpeedButton()
            }
            return
        }

        if (!hasSpeedPermission()) {
            pendingStartRealtime = true
            ActivityCompat.requestPermissions(
                this,
                arrayOf(PERMISSION_CAR_SPEED),
                REQUEST_CAR_SPEED
            )
            speedTextView.visibility = TextView.VISIBLE
            speedTextView.text = "속도 권한 요청 중..."
            return
        }

        try {
            manager.registerCallback(
                speedCallback,
                VehiclePropertyIds.PERF_VEHICLE_SPEED,
                CarPropertyManager.SENSOR_RATE_ONCHANGE
            )
            realtimeEnabled = true
            pendingStartRealtime = false
            speedTextView.visibility = TextView.VISIBLE
            speedTextView.text = "측정 중..."
            updateSpeedButton()
        } catch (e: SecurityException) {
            realtimeEnabled = false
            pendingStartRealtime = false
            speedTextView.visibility = TextView.VISIBLE
            speedTextView.text = "권한 없음"
            updateSpeedButton()
        } catch (e: Exception) {
            realtimeEnabled = false
            pendingStartRealtime = false
            speedTextView.visibility = TextView.VISIBLE
            speedTextView.text = "속도 측정 실패"
            updateSpeedButton()
            Toast.makeText(this, "실시간 속도 시작 실패", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRealtimeSpeed() {
        try {
            carPropertyManager?.unregisterCallback(
                speedCallback,
                VehiclePropertyIds.PERF_VEHICLE_SPEED
            )
        } catch (_: Exception) {
            // 연결 해제/미등록 상태 등은 무시
        }
        realtimeEnabled = false
        pendingStartRealtime = false
        speedTextView.text = ""
        speedTextView.visibility = TextView.GONE
        // 속도 정보가 없어졌으므로 mood 는 calm 으로 되돌립니다.
        PlaybackController.updateSpeed(0f)
        updateSpeedButton()
    }

    private fun updateSpeedButton() {
        fetchSpeedButton.text = if (realtimeEnabled) "실시간 속도 중지" else "실시간 속도 시작"
    }

    private fun hasSpeedPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            PERMISSION_CAR_SPEED
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAR_SPEED) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                if (pendingStartRealtime) {
                    startRealtimeSpeed()
                }
            } else {
                realtimeEnabled = false
                pendingStartRealtime = false
                speedTextView.text = "권한 없음"
                speedTextView.visibility = TextView.VISIBLE
                updateSpeedButton()
                Toast.makeText(this, "속도 접근 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun testInternetConnection() {
        statusTextView.text = "인터넷 연결 테스트 중..."
        testInternetButton.isEnabled = false

        // 네트워크 작업은 메인 스레드에서 수행할 수 없으므로 백그라운드 스레드 사용
        thread {
            var isConnected = false
            try {
                // 테스트용 공공/안전한 헬스체크 URL (예시)
                val url = URL("https://clients3.google.com/generate_204")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 3000 // 3초 타임아웃
                connection.readTimeout = 3000
                connection.connect()

                // HTTP 204 또는 200 응답이면 성공으로 판단
                if (connection.responseCode == 204 || connection.responseCode == 200) {
                    isConnected = true
                }
                connection.disconnect()
            } catch (e: Exception) {
                isConnected = false
            }

            // 결과를 다시 메인(UI) 스레드로 전달하여 텍스트 갱신
            runOnUiThread {
                statusTextView.text = if (isConnected) {
                    "인터넷 연결 상태: 정상 (Online)"
                } else {
                    "인터넷 연결 상태: 실패 (Offline)"
                }
                testInternetButton.isEnabled = true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startClock()
        if (naviConnected) startNaviPolling()
        // 마지막으로 확인된 위치가 있으면 날씨 갱신을 재개합니다.
        startWeatherRefreshIfNeeded()
    }

    override fun onPause() {
        // RoadJ 와 media player 두 앱이 데이터를 병행 수신할 수 있도록
        // 화면이 가려져도 위치/날씨 수집은 유지합니다. (해제는 onDestroy)
        super.onPause()
    }

    override fun onDestroy() {
        stopClock()
        stopNaviPolling()
        stopWeatherRefresh()
        // 진행 중인 날씨/주변 API 요청도 함께 정리합니다.
        ioScope.cancel()
        // 재생은 PlaybackController(앱 프로세스 수명)가 유지하므로 해제하지 않습니다.
        removePlayerListener?.invoke()
        removePlayerListener = null
        try {
            carPropertyManager?.unregisterCallback(speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED)
        } catch (_: Exception) {
        }
        // 더 이상 속도 콜백이 없으므로 mood 를 calm 으로 되돌립니다.
        if (realtimeEnabled) PlaybackController.updateSpeed(0f)
        car?.disconnect()

        naviHelper?.let { helper ->
            try {
                helper.removeListener(naviEventListener)
            } catch (_: Exception) {
            }
            try {
                helper.release()
            } catch (_: Exception) {
            }
        }
        naviHelper = null
        naviConnected = false

        super.onDestroy()
    }
}
