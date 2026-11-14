# RoadDJ Media Template (Android Automotive)

CAL `MediaPlaybackTemplate` 전용 앱입니다.

## 특징

- CAL API 8 이상: `MediaPlaybackTemplate`
  - MediaSession 기반 앨범 커버·곡 제목·가수+상황 태그·진행 바
  - PlaybackState 컨트롤 액션
- CAL API 4~7: 라이브 `PaneTemplate` 폴백
  - PaneTemplate 제목/Row 제목/Row 개수를 고정해 텍스트 변경을 refresh 로 처리
  - 현재 곡 제목과 현재 상황 태그를 5-step 쿼터 크래시 없이 표시
  - 액션은 API 제약상 최대 2개: 재생/일시정지, 다음 곡
- `MediaPlaybackManager.registerMediaPlaybackToken()` 로 세션 토큰 등록
- RoadJ와 독립적으로 NaviHelper/속도/날씨/Overpass 데이터 수신
- 태그는 현재 상황과 일치하는 것만 최대 3개 (언어 태그 제외)
- 차량 속도 권한은 첫 재생 시 요청
- 앱 이름: **RoadDJ Media Template**
- 앱 아이콘: 기본 Android 아이콘

## 모듈

| 모듈 | 설명 |
|---|---|
| `mediaplayertemplate` | CarAppService, MediaSession, PaneTemplate 폴백, 데이터 수집기 |
| `shared` | 로컬 플레이어, RANKING, 태그, 주변 조회, 공통 모델 |

## 빌드 준비

1. SDK 37, JDK 17+
2. `local.properties`
3. OpenWeather 키: `mediaplayertemplate/src/main/java/com/tikitacar/roadj/media/template/DrivingDataCollector.kt`
4. 음원/DB: `shared/src/main/assets/audio`, `tags.db` (저장소 미포함)

## 빌드/설치

```bash
./gradlew :mediaplayertemplate:assembleDebug
adb install -r mediaplayertemplate/build/outputs/apk/debug/mediaplayertemplate-debug.apk
```

패키지: `com.tikitacar.roadj.media.template`
