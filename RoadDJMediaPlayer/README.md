# RoadDJ player (Android Automotive)

RoadJ와 별도로 설치되는 커스텀 미디어 플레이어 앱입니다. 흑백 디자인과 앨범 커버/곡 제목/태그/진행 바/다음 곡 목록을 제공합니다.

## 특징

- 흑백(블랙/화이트/그레이) 미디어 UI
- RoaDJ 기본 앨범 커버
- 현재 곡 제목 · 가수 · 현재 상황 태그 (최대 3개, 언어 태그 제외)
- 진행 바 + 현재/전체 시간, ±10초 이동
- 이전/재생·일시정지/다음
- 다음 곡 목록 (미재생 곡, 최대 4개) 및 곡 선택 재생
- 재생한 곡 수 n/26, 중복 재생 방지
- MediaSession + MediaPlaybackManager 토큰 등록
- NaviHelper/날씨/Overpass/속도 데이터를 독립적으로 수신
- 75km/h 기준 mood, 새 곡 시작 시 주변 정보 갱신

## 모듈

| 모듈 | 설명 |
|---|---|
| `mediaplayer` | 커스텀 미디어 화면, MediaSession, 데이터 수집기 |
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
