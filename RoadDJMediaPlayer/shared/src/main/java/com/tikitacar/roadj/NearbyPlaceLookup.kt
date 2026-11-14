package com.tikitacar.roadj

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `geo_test/index.html` 의 위치 판별 로직을 Android용으로 옮긴 것.
 *
 * - Overpass `is_in` : 점을 포함하는 폴리곤(행정구역·수계·토지용도) 조회
 * - Overpass `around` : 근접한 선형/점형 feature(봉우리·하천선·해안선) 조회
 * - Nominatim : 주소/도시명 보조
 * - Photon : Overpass 결과가 없을 때 대체(근접 기반)
 *
 * 네트워크를 직접 호출하므로 반드시 백그라운드 스레드에서 호출해야 합니다.
 */
object NearbyPlaceLookup {

    private const val TAG = "NearbyGeo"

    // geo_test 기본 엔드포인트. User-Agent 없이 호출하면
    // "This service is only available to white-listed usages" 로 거부되므로 UA를 반드시 포함합니다.
    private const val OVERPASS_ENDPOINT = "https://overpass.openstreetmap.fr/api/interpreter"
    private const val NOMINATIM_ENDPOINT = "https://nominatim.openstreetmap.org/reverse"
    private const val PHOTON_ENDPOINT = "https://photon.komoot.io/reverse"
    private const val USER_AGENT = "RoadJ/1.0 (Android; com.tikitacar.roadj)"
    private const val TIMEOUT_MS = 15_000

    /** Overpass 두 쿼리(is_in + around)에 쓰는 전체 시간 예산. 초과 시 Photon 으로 대체합니다. */
    private const val OVERPASS_TOTAL_BUDGET_MS = 20_000L
    private const val OVERPASS_TIMEOUT_MS = 12_000

    /** 해안선까지 이 거리 이내이고 바다 쪽에 있으면 바다로 판정합니다. */
    private const val SEA_MAX_DISTANCE_METERS = 1_500.0
    private const val METERS_PER_DEGREE = 111_320.0

    /**
     * 판정 우선순위 순서: 바다 > 강·물 > 산 > 도시.
     * 앞 카테고리에서 하나라도 조건을 만족하면 뒤 카테고리는 보지 않습니다.
     */
    private val CATEGORIES = listOf(
        "바다" to Regex("^(natural=(coastline|bay|strait)|water=(sea|ocean|bay)|place=(sea|ocean))"),
        "강·물" to Regex("^(natural=(water|wetland|spring)|waterway=|landuse=(reservoir|basin))"),
        "산" to Regex("^(natural=(peak|ridge|cliff|volcano|hill)|landuse=quarry)"),
        "도시" to Regex("^(place=|boundary=administrative|landuse=(residential|commercial|industrial|retail|construction))")
    )

    data class Category(
        val label: String,
        val count: Int,
        val nearestMeters: Int?,
        val contains: Boolean,
        val sampleNames: List<String>
    )

    data class Result(
        val verdict: String,
        val reason: String,
        val displayName: String,
        val categories: List<Category>,
        val source: String
    )

    private data class Element(
        val kv: List<String>,
        val name: String,
        val contains: Boolean,
        val distance: Int
    )

    /**
     * 현재 좌표 주변 판정을 수행합니다. (blocking)
     * @param radiusMeters `around` 조회 반경 (geo_test 기본값 400m)
     */
    fun lookup(latitude: Double, longitude: Double, radiusMeters: Int): Result {
        // Overpass 는 서버 혼잡 시 오래 걸릴 수 있으므로 전체 예산을 두고, 실패하면 Photon 으로 대체
        val overpassDeadline = System.currentTimeMillis() + OVERPASS_TOTAL_BUDGET_MS
        val areaElements = runOverpass(queryArea(latitude, longitude), overpassDeadline)
        val nearElements = runOverpass(queryNear(latitude, longitude, radiusMeters), overpassDeadline)

        val elements = ArrayList<Element>()
        areaElements.forEach { json ->
            parseElement(json, latitude, longitude, contains = true)?.let { elements += it }
        }
        nearElements.forEach { json ->
            parseElement(json, latitude, longitude, contains = false)?.let { elements += it }
        }

        // 한국 OSM에는 바다 폴리곤이 거의 없어 is_in 으로 잡히지 않습니다.
        // 해안선이 SEA_MAX_DISTANCE_METERS 이내면 바다로 판정합니다. (1순위)
        detectSea(nearElements, latitude, longitude)?.let { elements += it }

        val nominatim = runNominatim(latitude, longitude)

        // Overpass 결과가 없으면 Photon 으로 대체 (geo_test 와 동일)
        var source = "Overpass"
        if (elements.isEmpty()) {
            source = "Photon 대체"
            elements += runPhoton(latitude, longitude)
        }
        Log.d(
            TAG,
            "lookup lat=$latitude lon=$longitude area=${areaElements.size} " +
                "near=${nearElements.size} source=$source"
        )

        val rows = CATEGORIES.map { (label, regex) ->
            val hits = elements
                .filter { element -> element.kv.any { regex.containsMatchIn(it) } }
                .sortedBy { it.distance }
            Category(
                label = label,
                count = hits.size,
                nearestMeters = hits.firstOrNull()?.distance?.takeIf { it != Int.MAX_VALUE },
                contains = hits.any { it.contains },
                sampleNames = hits.take(3).map { hit ->
                    hit.name.ifBlank { hit.kv.joinToString(" ") }
                }
            )
        }

        val address = nominatim?.optJSONObject("address")
        val hasAddress = address != null && address.length() > 0
        val displayName = nominatim?.optString("display_name").orEmpty()

        // 카테고리별 인정 거리. 바다 > 강·물 > 산 > 도시 우선순위로 먼저 만족한 쪽을 선택합니다.
        // 바다는 해안선 근접(1.5km) 또는 바다 폴리곤 포함, 도시는 폴리곤 포함만 인정.
        val limits = mapOf(
            "바다" to SEA_MAX_DISTANCE_METERS.toInt(),
            "강·물" to radiusMeters,
            "산" to radiusMeters,
            "도시" to 0
        )
        val pick = rows.firstOrNull { row ->
            val limit = limits[row.label] ?: 0
            row.count > 0 && (row.contains || ((row.nearestMeters ?: Int.MAX_VALUE) <= limit))
        }

        var verdict: String? = pick?.label
        var reason = pick?.let {
            "최근접 ${it.sampleNames.firstOrNull() ?: "-"} (${it.nearestMeters ?: 0}m)"
        } ?: "매칭 feature 없음"

        if (verdict == null && !hasAddress && rows[0].count > 0) {
            verdict = "바다"
            reason = "해안선 근접 + 주소 없음"
        }
        if (verdict == null && nominatim != null) {
            verdict = "도시"
            reason = "주소만 존재"
        }
        if (verdict == null) {
            verdict = "미상"
            reason = "두 API 모두 판정 불가 (바다/해외 추정)"
        }

        return Result(
            verdict = verdict,
            reason = reason,
            displayName = displayName,
            categories = rows,
            source = source
        )
    }

    // ---------------------------------------------------------------- queries

    // is_in = 점을 포함하는 폴리곤(행정구역·수계·토지용도)
    private fun queryArea(lat: Double, lon: Double): String =
        "[out:json][timeout:25];is_in($lat,$lon);out tags 80;"

    // 선형 feature 는 중심이 멀어 center 거리로 못 잡으므로 bbox 로 형상을 잘라 최근접 노드 거리 계산
    private fun queryNear(lat: Double, lon: Double, radius: Int): String {
        val dLat = (max(radius, 1500) + 100) / 111_320.0
        val dLon = dLat / cos(Math.toRadians(lat))
        val bbox = String.format(
            Locale.US,
            "%.5f,%.5f,%.5f,%.5f",
            lat - dLat, lon - dLon, lat + dLat, lon + dLon
        )
        return """
            [out:json][timeout:25];
            (
              node(around:$radius,$lat,$lon)[natural~"^(peak|ridge|cliff|volcano|hill)$"];
              way(around:$radius,$lat,$lon)[waterway];
              way(around:1500,$lat,$lon)[natural=coastline];
            );
            out tags geom($bbox) 80;
        """.trimIndent()
    }

    // ---------------------------------------------------------------- parsing

    private fun parseElement(
        json: JSONObject,
        lat: Double,
        lon: Double,
        contains: Boolean
    ): Element? {
        val tags = json.optJSONObject("tags") ?: return null

        // 해안선은 detectSea() 에서 선분 거리로 한 번만 계산하므로 중복 집계하지 않습니다.
        if (!contains && tags.optString("natural") == "coastline") return null

        // 지하 암거/터널 하천은 지표 판정에서 제외
        if (tags.optString("waterway").isNotBlank() &&
            (tags.has("tunnel") || tags.has("covered") || tags.optString("layer") == "-1")
        ) {
            return null
        }

        val kv = ArrayList<String>()
        val keys = tags.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            kv += "$key=${tags.optString(key)}"
        }

        val name = tags.optString("name:ko").ifBlank { tags.optString("name") }

        val points = ArrayList<Pair<Double, Double>>()
        val geometry = json.optJSONArray("geometry")
        if (geometry != null) {
            for (i in 0 until geometry.length()) {
                val point = geometry.optJSONObject(i) ?: continue
                if (!point.has("lon") || !point.has("lat")) continue
                points += point.optDouble("lat") to point.optDouble("lon")
            }
        }
        if (points.isEmpty() && json.has("lat") && json.has("lon")) {
            points += json.optDouble("lat") to json.optDouble("lon")
        }

        val distance = when {
            contains -> 0
            points.isEmpty() -> Int.MAX_VALUE
            else -> points.minOf { (pLat, pLon) ->
                distanceMeters(lat, lon, pLat, pLon)
            }.toInt()
        }

        return Element(kv = kv, name = name, contains = contains, distance = distance)
    }

    private data class CoastHit(
        val distanceMeters: Double,
        val cross: Double,
        val name: String
    )

    /**
     * 해안선이 가까우면 바다로 판정합니다. (우선순위 1위)
     *
     * - 한국 OSM에는 바다 폴리곤이 거의 없어 `is_in` 으로는 잡히지 않습니다.
     * - 해안선은 선(line)이라 `contains` 가 될 수 없으므로 최근접 선분까지의 거리로 판정합니다.
     * - OSM 규칙(진행 방향 기준 왼쪽=육지, 오른쪽=바다)은 로그로만 남기고,
     *   SEA_MAX_DISTANCE_METERS 이내면 어느 쪽이든 바다로 인정합니다.
     */
    private fun detectSea(
        nearElements: List<JSONObject>,
        lat: Double,
        lon: Double
    ): Element? {
        val hit = nearestCoastline(nearElements, lat, lon) ?: return null
        if (hit.distanceMeters > SEA_MAX_DISTANCE_METERS) return null

        val side = if (hit.cross < 0.0) "SEA" else "LAND"
        Log.d(TAG, "coastline ${hit.distanceMeters.toInt()}m side=$side")
        return Element(
            kv = listOf("natural=coastline"),
            name = hit.name.ifBlank { "해안선" },
            contains = false,
            distance = hit.distanceMeters.roundToInt()
        )
    }

    /** 모든 해안선 way 의 선분을 훑어 최근접 선분 거리/방향/이름을 반환합니다. */
    private fun nearestCoastline(
        nearElements: List<JSONObject>,
        lat: Double,
        lon: Double
    ): CoastHit? {
        val cosLat = cos(Math.toRadians(lat))
        val px = lon * cosLat
        val py = lat

        var bestDistance = Double.MAX_VALUE
        var bestCross = 0.0
        var bestName = ""

        for (element in nearElements) {
            val tags = element.optJSONObject("tags") ?: continue
            if (tags.optString("natural") != "coastline") continue
            val geometry = element.optJSONArray("geometry") ?: continue

            var prevLat = Double.NaN
            var prevLon = Double.NaN
            for (i in 0 until geometry.length()) {
                val node = geometry.optJSONObject(i)
                if (node == null || !node.has("lat") || !node.has("lon")) {
                    // bbox 밖 노드는 null 로 오므로 선분 연결을 끊습니다.
                    prevLat = Double.NaN
                    prevLon = Double.NaN
                    continue
                }
                val curLat = node.optDouble("lat")
                val curLon = node.optDouble("lon")

                if (!prevLat.isNaN()) {
                    val ax = prevLon * cosLat
                    val ay = prevLat
                    val bx = curLon * cosLat
                    val by = curLat
                    val dx = bx - ax
                    val dy = by - ay
                    val len2 = dx * dx + dy * dy
                    if (len2 > 0.0) {
                        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
                        val cx = ax + t * dx
                        val cy = ay + t * dy
                        val dxm = (px - cx) * METERS_PER_DEGREE
                        val dym = (py - cy) * METERS_PER_DEGREE
                        val distance = sqrt(dxm * dxm + dym * dym)

                        if (distance < bestDistance) {
                            bestDistance = distance
                            bestCross = dx * (py - ay) - dy * (px - ax)
                            bestName = tags.optString("name:ko")
                                .ifBlank { tags.optString("name") }
                        }
                    }
                }
                prevLat = curLat
                prevLon = curLon
            }
        }

        if (bestDistance == Double.MAX_VALUE) return null
        return CoastHit(bestDistance, bestCross, bestName)
    }

    private fun runOverpass(query: String, deadline: Long): List<JSONObject> {
        // 같은 IP로 동시 요청 시 큐잉/타임아웃되므로 순차 실행
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0L) return emptyList()

        val timeout = remaining.coerceAtMost(OVERPASS_TIMEOUT_MS.toLong()).toInt()
        val body = httpPost(OVERPASS_ENDPOINT, "data=" + urlEncode(query), timeout) ?: return emptyList()
        return try {
            JSONObject(body).optJSONArray("elements")?.toObjectList().orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Overpass 응답 파싱 실패", e)
            emptyList()
        }
    }

    private fun runNominatim(lat: Double, lon: Double): JSONObject? {
        val url = "$NOMINATIM_ENDPOINT?format=jsonv2&lat=$lat&lon=$lon" +
            "&zoom=18&addressdetails=1&accept-language=ko"
        val body = httpGet(url) ?: return null
        return try {
            val json = JSONObject(body)
            if (json.has("error")) {
                Log.w(TAG, "Nominatim 오류: ${json.optString("error")}")
                null
            } else {
                json
            }
        } catch (e: Exception) {
            Log.w(TAG, "Nominatim 응답 파싱 실패", e)
            null
        }
    }

    private fun runPhoton(lat: Double, lon: Double): List<Element> {
        val body = httpGet("$PHOTON_ENDPOINT?lat=$lat&lon=$lon&limit=20") ?: return emptyList()
        return try {
            val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
            val result = ArrayList<Element>()
            for (i in 0 until features.length()) {
                val properties = features.optJSONObject(i)
                    ?.optJSONObject("properties") ?: continue
                val osmKey = properties.optString("osm_key")
                if (osmKey.isBlank()) continue
                val kv = listOf("$osmKey=${properties.optString("osm_value")}")
                val name = properties.optString("name")
                    .ifBlank { properties.optString("city") }

                val coords = features.optJSONObject(i)
                    ?.optJSONObject("geometry")
                    ?.optJSONArray("coordinates")
                val distance = if (coords != null && coords.length() >= 2) {
                    distanceMeters(lat, lon, coords.optDouble(1), coords.optDouble(0)).toInt()
                } else {
                    0
                }
                result += Element(kv = kv, name = name, contains = false, distance = distance)
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Photon 응답 파싱 실패", e)
            emptyList()
        }
    }

    // ---------------------------------------------------------------- http

    private fun httpPost(url: String, body: String, timeoutMs: Int = TIMEOUT_MS): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            readResponse(connection)
        } catch (e: Exception) {
            Log.w(TAG, "POST 실패: $url", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun httpGet(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            readResponse(connection)
        } catch (e: Exception) {
            Log.w(TAG, "GET 실패: $url", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun readResponse(connection: HttpURLConnection): String? {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            Log.w(TAG, "HTTP $code: ${text.take(160)}")
            return null
        }
        return text
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun JSONArray.toObjectList(): List<JSONObject> {
        val list = ArrayList<JSONObject>(length())
        for (i in 0 until length()) {
            optJSONObject(i)?.let { list += it }
        }
        return list
    }

    // ---------------------------------------------------------------- util

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * earthRadius * asin(sqrt(h))
    }
}
