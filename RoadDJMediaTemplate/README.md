# RoadDJ Media Template (Android Automotive)

CAL `MediaPlaybackTemplate` / API 4 `PaneTemplate` 기반의 미디어 템플릿 앱입니다.

## 특징

- CAL API 8 이상: `MediaPlaybackTemplate`
  - MediaSession 기반 앨범 커버·곡 제목·가수+상황 태그·진행 바
  - PlaybackState 컨트롤 액션
- CAL API 4~7: 원래의 `PaneTemplate` 폴백
  - PaneTemplate 제목/Row 제목/Row 개수를 고정해 텍스트 변경을 refresh로 처리
  - 현재 곡 제목과 현재 상황 태그를 5-step 쿼터 크래시 없이 표시
  - 액션은 API 제약상 최대 2개: 재생/일시정지, 다음 곡
- **언어 선택 옵션 없음** (원래 구성 유지)
- RoadDJ player에서 Drive 진입 시 상태 handoff 수신:
  - 선택 언어
  - 현재 곡 / 재생 위치 / 재생 여부 / 재생 이력
- 수동으로 앱을 열면 RoadDJ player에 현재 상태를 요청해 이어서 재생
- Drive handoff로 진입한 경우에만 실제 Drive 기어를 확인한 뒤 Park/Neutral에서 player로 자동 복귀
- `MediaPlaybackManager.registerMediaPlaybackToken()`로 세션 토큰 등록
- RoadJ와 독립적으로 NaviHelper/속도/날씨/Overpass 데이터 수신
- 태그는 현재 상황과 일치하는 것만 최대 3개 (언어 태그 제외)
- 차량 속도/기어 권한은 필요 시 요청
- 앱 이름: **RoadDJ Media Template**

## 모듈

| 모듈 | 설명 |
|---|---|
| `mediaplayertemplate` | CarAppService, MediaSession, PaneTemplate 폴백, 상태 handoff, 데이터 수집기 |
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
