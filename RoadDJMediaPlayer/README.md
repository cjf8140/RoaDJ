# RoadDJ media player (Android Automotive)

RoadJ와 **별도 앱**으로 설치되는 차량용 미디어 플레이어입니다.  
RoadJ의 데이터 화면은 표시하지 않고, 미디어 재생 화면만 제공합니다.

## 특징

- Car App Library `MediaPlaybackTemplate` (CAL API 8+)
- 구형 호스트(CAL API 4~7)용 고정 `MessageTemplate` 폴백
  - 템플릿을 다시 보내지 않아 **5-step 쿼터 초과 크래시를 방지**
- `MediaSessionCompat` + `MediaPlaybackManager.registerMediaPlaybackToken()`
- RoadJ와 **독립적으로** 동일한 데이터 소스를 수신
  - Pleos NaviHelper 현재 위치 (3초 폴링)
  - 차량 속도 → 75km/h 기준 mood(happy/calm)
  - OpenWeather 현재 날씨 (2분 주기, 현재 위치 기준)
  - Overpass 주변 판정 (최초 위치 + 새 곡 시작 시)
- 위치/날씨/주변 정보 텍스트는 표시하지 않고 미디어 정보만 표시
- 새 곡 시작 시 주변 정보 갱신
- 첫 재생 시점에 차량 속도 권한 요청 (앱 시작 화면을 가리지 않음)

## 모듈

| 모듈 | 설명 |
|---|---|
| `mediaplayer` | CarAppService, MediaSession, 헤드리스 데이터 수집기 |
| `shared` | 로컬 플레이어, RANKING, 주변 조회, 공통 모델 |

## 빌드 준비

1. Android Studio (AGP 9.x, compileSdk 37, JDK 17+)
2. 루트에 `local.properties` 생성
   ```properties
   sdk.dir=C\:\\Users\\<user>\\AppData\\Local\\Android\\Sdk
   ```
3. OpenWeather API 키 설정  
   `mediaplayer/src/main/java/com/tikitacar/roadj/media/DrivingDataCollector.kt`
   ```kotlin
   private const val OPEN_WEATHER_API_KEY = "YOUR_OPENWEATHER_API_KEY"
   ```
4. **음원/DB는 저장소에 포함하지 않습니다.** (Labeler와 동일한 정책)
   - `shared/src/main/assets/audio/*.mp3` — 26곡
   - `shared/src/main/assets/tags.db`
   - `Labeler/audio/`, `Labeler/tags.db`를 위 경로에 복사해 넣으세요.
5. 차량 호스트가 CAL API 4 이상이어야 합니다. (API 8+에서 MediaPlaybackTemplate 사용)

## 빌드

```bash
./gradlew :mediaplayer:assembleDebug
```

APK: `mediaplayer/build/outputs/apk/debug/mediaplayer-debug.apk`

## 설치

```bash
adb install -r mediaplayer/build/outputs/apk/debug/mediaplayer-debug.apk
```

## 참고

- 패키지: `com.tikitacar.roadj.media` (RoadJ와 별개)
- 앱 이름: **RoadDJ media player**
- OpenWeather API 키는 공개 저장소에 커밋하지 마세요.
