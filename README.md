# RoadDJ

Android Automotive(AAOS / Pleos Connect) 환경을 위한 **단일 앱**입니다.

기존의 `RoadDJ player`, `RoadDJ Media Template`, `RoadDJ tester`를 하나로 합쳤습니다.
Park/Neutral에서는 커스텀 플레이어 화면, Drive 계열 기어에서는 CAL 템플릿 화면으로
**같은 앱 안에서 씬(scene)만 교체**합니다.

- 앱 이름: **RoadDJ**
- 패키지: `com.tikitacar.roadj.media`
- 버전: `1.0` (`versionCode 1`)
- 지원: Android Automotive, compileSdk 37, minSdk 29, targetSdk 37

---

## 1. 주요 기능

### 미디어 재생

- 로컬 `assets/audio/*.mp3` 재생 (MediaPlayer)
- `RANKING.ko.md` 규칙 기반 곡 정렬
- 다음 곡 선택 시마다 최신 주행 상태로 재정렬
- 곡 종료 3초 전 다음 순서 갱신 + 이미 재생한 곡 제외
- 재생 이력 기반 중복 방지
- MediaSession + MediaPlaybackManager 토큰 등록
- 이전 / 재생·일시정지 / 다음 / ±10초 이동
- 진행 바, 현재/전체 시간
- 다음 곡 목록(최대 4곡) 및 곡 선택 재생

### 곡 언어 선택

플레이어 화면 우측 상단 `언어` 버튼에서 목록으로 선택합니다.

- 전체
- 한국어
- English
- 日本語

동작:

- **전체**: 모든 언어의 곡
- **언어 선택**: 해당 언어 곡만 재생
- 기존 RANKING 우선순위(event → precip → place → time → season → BPM)는 그대로 유지
- 선택값은 SharedPreferences에 저장
- 언어를 바꿔도 현재 재생 중인 곡의 메타데이터는 유지되고,
  다음 곡부터 새 언어 목록으로 넘어감

### 태그 표시

- 곡별 태그 중 **현재 주행 상황과 일치하는 태그만** 최대 3개 표시
- 우선순위: event → precip → place → time → season
- 일치 태그가 3개 미만이면 BPM 상위 50% → `#happy`, 하위 50% → `#calm`
- 언어 태그는 화면에 표시하지 않음
- 예: `#도시 #밤 #calm`

### 주행 데이터

- Pleos NaviHelper 현재 위치 / 목적지
- OpenWeather 현재 날씨, 2분 주기 갱신
- 일출/일몰 기반 낮/밤 판정
- 위치 시간대 기준 현재 시간/계절/이벤트
- Overpass / Nominatim / Photon 기반 주변 판정
  - 바다 > 강·물 > 산 > 도시
- 차량 속도 75km/h 기준 mood
  - 75km/h 이상: `happy`
  - 75km/h 미만: `calm`
- 새 곡 시작 시 주변 정보 갱신

---

## 2. Park/Drive 씬 전환

| 차량 상태 | 화면 | 구현 |
|---|---|---|
| Park / Neutral | RoadDJ 커스텀 플레이어 | `MediaPlayerActivity` |
| Drive / Reverse / 주행 기어 | CAL 템플릿 화면 | `CarAppActivity` + `MediaCarAppService` |

- 기어 감지: `VehiclePropertyIds.GEAR_SELECTION`, `CURRENT_GEAR`
- 권한: `android.car.permission.CAR_POWERTRAIN`
- 기어 변경 시 1.5초 안정화 후 씬 전환
- 기어 정보를 사용할 수 없는 경우 속도 5km/h 이상이면 템플릿 씬으로 폴백
- 두 씬은 **같은 앱, 같은 프로세스**의 `PlaybackController`를 공유
  - 선택 언어
  - 현재 곡
  - 재생 위치
  - 재생/일시정지 상태
  - 재생 이력
- Activity만 교체되므로 오디오 재생은 같은 MediaPlayer에서 계속됩니다.

### Drive 씬 (CAL)

- CAL API 8 이상: `MediaPlaybackTemplate`
- CAL API 4~7: `PaneTemplate` 폴백
  - 템플릿 제목/Row 제목/Row 개수를 고정해 5-step 쿼터 없이 텍스트 갱신
  - 현재 곡 + 가수 + 상황 태그 + 재생 상태 표시
  - 액션: 재생/일시정지, 다음 곡

---

## 3. 주행 중 화면(DO) 관련

- `MediaPlayerActivity`와 `CarAppActivity` 모두 manifest에
  `distractionOptimized=true`가 선언되어 있습니다.
- Drive 씬은 호스트가 렌더링하는 CAL 템플릿이므로 DO 기준을 호스트가 관리합니다.
- 커스텀 플레이어 화면은 Park/Neutral에서만 표시됩니다.
- 실제 차량에서는 OEM/Pleos의 앱 허용 목록(allowlist) 또는 심사가 필요할 수 있습니다.
- `distractionOptimized=true`는 필요 조건이며 최종 승인을 보장하지 않습니다.

---

## 4. 프로젝트 구조

```text
RoadJ/
├─ mediaplayer/                 # RoadDJ 앱 (단일 APK)
│  ├─ src/main/java/com/tikitacar/roadj/media/
│  │  ├─ MediaPlayerActivity.kt       # Park/Neutral 커스텀 플레이어
│  │  ├─ MediaPlaybackScreen.kt       # Drive 씬 (PaneTemplate/MediaPlaybackTemplate)
│  │  ├─ MediaCarAppService.kt        # CAL CarAppService
│  │  ├─ SceneController.kt           # 기어 기반 씬 전환
│  │  ├─ DrivingDataCollector.kt      # 위치/날씨/주변/속도/기어
│  │  ├─ MediaSessionController.kt
│  │  ├─ MediaPlayerApplication.kt
│  │  └─ MediaDisplay.kt
│  └─ src/main/res/
│     ├─ layout/activity_media_player.xml
│     ├─ layout/dialog_language.xml
│     └─ ...
├─ shared/                      # 공통 라이브러리
│  ├─ LocalAudioPlayer.kt
│  ├─ PlaybackController.kt
│  ├─ SongRanker.kt
│  ├─ SongTagFormatter.kt
│  ├─ NearbyPlaceLookup.kt
│  ├─ RankingMood.kt
│  └─ shared/MyMusicService.kt
├─ dist-apk/
│  ├─ RoadDJ-1.0-debug.apk
│  └─ INSTALL.txt
├─ README.md
├─ settings.gradle.kts
└─ build.gradle.kts
```

> `automotive`(RoadDJ tester), `mediaplayertemplate`(RoadDJ Media Template),
> `mobile` 모듈은 제거되었습니다.

---

## 5. 빌드 준비

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

4. 음원/DB 준비

   저장소에는 음원과 `tags.db`를 포함하지 않습니다.

   ```text
   shared/src/main/assets/audio/*.mp3   (26곡)
   shared/src/main/assets/tags.db
   ```

   `Labeler/audio/`, `Labeler/tags.db`를 위 경로에 복사합니다.

---

## 6. 빌드

```bash
./gradlew :mediaplayer:assembleDebug
```

APK:

```text
mediaplayer/build/outputs/apk/debug/mediaplayer-debug.apk
```

로컬 배포용 APK (저장소에는 APK를 포함하지 않습니다):

```text
dist-apk/RoadDJ-1.0-debug.apk
```

---

## 7. 설치

```bash
adb install -r dist-apk/RoadDJ-1.0-debug.apk
```

패키지:

```text
com.tikitacar.roadj.media
```

---

## 8. 권한

```xml
<uses-permission android:name="androidx.car.app.MEDIA_TEMPLATES" />
<uses-permission android:name="android.car.permission.CAR_SPEED" />
<uses-permission android:name="android.car.permission.CAR_POWERTRAIN" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="pleos.car.permission.NAVI_ROUTE" />
```

---

## 9. 배포 시 주의

- Debug APK는 Android Debug 키로 서명되어 있습니다.
- 정식 배포(Release) APK는 keystore 생성 + `signingConfig` 설정이 필요합니다.
- APK 안에는 다음이 포함됩니다.
  - 로컬 음원 26곡
  - `tags.db`
  - OpenWeather API 키
- GitHub Releases 등 public 배포 시:
  - 음원 저작권
  - API 키 노출

에 주의해야 합니다. 필요하면 음원을 제외한 빌드 또는 private 배포를 사용하세요.

---

## 10. 검증 상태

- `:mediaplayer:assembleDebug` 빌드 성공
- APK 라벨 `RoadDJ` 확인
- 패키지 `com.tikitacar.roadj.media`
- `MediaPlayerActivity` + `CarAppActivity` + `MediaCarAppService` 포함
- `assets/audio` 26곡 + `tags.db` 포함
- Debug 서명 확인
- 기존 RoadDJ tester / RoadDJ Media Template 앱 제거
