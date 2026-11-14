# RoadDJ tester (Android Automotive)

Pleos Connect(AAOS) 환경에서 주행 정보를 보여주고, RANKING 규칙으로 정렬한 로컬 음악을 재생하는 앱입니다.

## 기능

- 실시간 차량 속도 (`android.car.permission.CAR_SPEED`)
- Pleos NaviHelper 현재 위치 / 목적지 (`pleos.car.permission.NAVI_ROUTE`)
- OpenWeather 현재 날씨 — 현재 위치 기준 2분 주기 갱신
- 현재 시간(AM/PM, 현재 위치 시간대) · 월 · 계절 · 일출/일몰
- 주변 판정: 바다 > 강·물 > 산 > 도시 (Overpass / Nominatim / Photon)
- `RANKING.ko.md` 규칙 기반 로컬 음악 26곡 재생
- 곡 언어 선택(한국어/English/日本語) + 선택값 저장
- 실시간 속도 75km/h 이상 → mood=happy, 미만 → mood=calm
- 새 곡 시작 시 주변 정보 갱신
- 다음 곡 선택 시마다 최신 상태로 RANKING 재계산
- 곡 종료 3초 전 순서 갱신 + 재생한 곡 제외
- 태그: 현재 상황과 일치하는 태그 최대 3개 (화면에서는 언어 태그 제외)

## 모듈

| 모듈 | 설명 |
|---|---|
| `automotive` | 앱 UI, 위치/날씨/주변/속도 수집 |
| `shared` | 로컬 플레이어, RANKING, 태그, 주변 조회, 공통 모델 |

## 빌드 준비

1. Android Studio (AGP 9.x, compileSdk 37, JDK 17+)
2. 루트에 `local.properties` 생성
   ```properties
   sdk.dir=C\:\\Users\\<user>\\AppData\\Local\\Android\\Sdk
   ```
3. OpenWeather API 키 설정  
   `automotive/src/main/java/com/tikitacar/roadj/MainActivity.kt`
   ```kotlin
   private const val OPEN_WEATHER_API_KEY = "YOUR_OPENWEATHER_API_KEY"
   ```
4. 음원/DB는 저장소에 포함하지 않습니다.
   - `shared/src/main/assets/audio/*.mp3` (26곡)
   - `shared/src/main/assets/tags.db`
   - `Labeler/audio/`, `Labeler/tags.db`를 위 경로에 복사해 넣으세요.

## 빌드

```bash
./gradlew :automotive:assembleDebug
```

APK: `automotive/build/outputs/apk/debug/automotive-debug.apk`
