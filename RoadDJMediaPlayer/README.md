# RoadDJ player (Android Automotive)

RoadJ와 별도로 설치되는 커스텀 미디어 플레이어 앱입니다.
흑백 디자인과 앨범 커버/곡 제목/태그/진행 바/다음 곡 목록을 제공합니다.

## 특징

- 흑백(블랙/화이트/그레이) 미디어 UI
- RoaDJ 기본 앨범 커버
- 현재 곡 제목 · 가수 · 현재 상황 태그 (최대 3개, 언어 태그 제외)
- 진행 바 + 현재/전체 시간, ±10초 이동
- 이전/재생·일시정지/다음
- 다음 곡 목록 (미재생 곡, 최대 4개) 및 곡 선택 재생
- 재생한 곡 수 n/26, 중복 재생 방지
- **곡 언어 선택 목록** (전체 / 한국어 / English / 日本語)
  - 전체: 모든 언어
  - 언어 선택: 해당 언어 곡만, 기존 RANKING 우선순위 유지
  - 선택값 저장
- MediaSession + MediaPlaybackManager 토큰 등록
- NaviHelper/날씨/Overpass/속도 데이터를 독립적으로 수신
- 75km/h 기준 mood, 새 곡 시작 시 주변 정보 갱신

## Park/Drive 씬 전환

- Park / Neutral: 이 앱의 커스텀 플레이어 화면
- Drive / Reverse / 주행 기어: **RoadDJ Media Template 앱**으로 자동 전환
- 전환 시 현재 상태를 handoff로 전달:
  - 선택 언어
  - 현재 곡
  - 재생 위치(ms)
  - 재생/일시정지 상태
  - 재생 이력(중복 방지)
- player 앱이 백그라운드에서 종료되어도 마지막 곡/위치를 저장해 template이 이어받을 수 있습니다.
- 기어 감지에는 `android.car.permission.CAR_POWERTRAIN`을 사용합니다.

## 모듈

| 모듈 | 설명 |
|---|---|
| `mediaplayer` | 커스텀 미디어 화면, MediaSession, 씬 전환, 데이터 수집기 |
| `shared` | 로컬 플레이어, RANKING, 태그, 주변 조회, 공통 모델 |

## 빌드 준비

1. SDK 37, JDK 17+
2. `local.properties`
3. OpenWeather 키: `mediaplayer/src/main/java/com/tikitacar/roadj/media/DrivingDataCollector.kt`
4. 음원/DB: `shared/src/main/assets/audio`, `tags.db` (Labeler와 동일한 정책으로 저장소 미포함)

## 빌드/설치

```bash
./gradlew :mediaplayer:assembleDebug
adb install -r mediaplayer/build/outputs/apk/debug/mediaplayer-debug.apk
```

패키지: `com.tikitacar.roadj.media`
