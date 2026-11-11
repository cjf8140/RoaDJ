# 랭킹 규칙

차량의 현재 상태를 곡 순서로 바꾸는 규칙. 점수 계산과 정렬을 전부 SQL 한
쿼리로 처리하므로 앱은 결과를 그대로 쓰면 된다.

## DB

`tags.db` (SQLite). `db.py`가 `tags.json`에서 매 실행마다 새로 만든다.

```
songs
  id, file, title, artist
  bpm, bpm_source, energy, valence, danceability, model
  event, precip, place, time, season
```

곡 하나가 한 행이다. 태그는 필드 하나가 컬럼 하나이고, 값이 여러 개면 콤마로
붙인다. `NULL`은 그 태그가 없음을 뜻한다.

```
id                            bpm    event       precip  place  time        season
Mrs. GREEN APPLE - Magic      117.5  NULL        NULL    NULL   night       NULL
Mrs. GREEN APPLE - Lilac       92.4  NULL        rain    NULL   day,night   spring,summer
Ariana Grande - Santa Tell Me 191.9  christmas   NULL    NULL   night       winter
```

콤마 조회는 값 양옆에도 콤마를 붙여서 한다. `rain`이 나중에 추가될 `rainy`와
걸리지 않게 하기 위해서다.

```sql
SELECT artist, title FROM songs
WHERE ',' || precip || ',' LIKE '%,rain,%';
```

`NULL`의 의미는 필드에 따라 다르다. 이 차이는 아래 규칙에서 그대로 쓰인다.

- `event`, `precip`, `place`: 빈 태그가 **값**이다. 행사 없음, 맑음, 특정 장소
  없음.
- `time`, `season`: 빈 태그는 **모름**이다. 가사가 시간대나 계절을 말하지
  않았을 뿐이다.

## 규칙

### 상태

모든 카테고리는 항상 값이 있다. 시계가 시간과 계절을 알고 날씨 피드가 강수를
알기 때문에 차는 언제나 값을 보낸다. "지정 안 함" 상태는 없다.

```
state = { event, precip, place, time, season, mood }
```

### 태그 의미

| 카테고리 | 값 | 빈 태그의 의미 | 점수 범위 |
| --- | --- | --- | --- |
| `event` | `none`, `christmas` | `none` (행사 없음) | +1 / -1 |
| `precip` | `none`, `rain`, `snow` | `none` (맑음) | +1 / -1 |
| `place` | `none`, `mountain`, `sea`, `river`, `city` | `none` (특정 장소 없음) | +1 / -1 |
| `time` | `day`, `night` | 모름 | +1 / 0 / -1 |
| `season` | `spring`, `summer`, `autumn`, `winter` | 모름 | +1 / 0 / -1 |
| `mood` | `happy`, `calm` | 태그 없음 | 정렬 방향 |

`event`, `precip`, `place`는 **열거적**이다. 태그가 없는 곡은 정말로 "행사
없음", "맑음", "특정 장소 없음"이므로 빈 태그를 다른 값과 똑같이 비교한다.

`time`과 `season`은 열거적이지 않다. 낮이나 밤을 말하지 않은 곡이 "낮 곡"이
되는 것도, 계절을 언급하지 않은 곡이 "계절 없음"이 되는 것도 아니다. 그냥 입을
다문 것이다. 이런 곡은 어떤 요청에도 0점이다.

### 점수

```
score(song, category):
    if category == mood:
        return 없음                   # mood는 bpm 방향만 정한다

    values  = song.tags[category]     # 리스트, 비어 있을 수 있음
    request = state[category]

    if values가 비었으면:
        if category in (event, precip, place):
            return request == "none"이면 +1, 아니면 -1
        return 0                      # time, season: 모름, 중립

    if request == "none":
        return -1                     # none을 원했는데 곡이 값을 주장함

    return request in values이면 +1, 아니면 -1
```

### 정렬

```
priority = [event, precip, place, time, season]     # 위가 높은 우선순위

compare(a, b):
    for category in priority:
        d = score(b, category) - score(a, category)
        if d != 0: return d
    d = (a.bpm - b.bpm) * (mood == happy ? -1 : 1)
    if d != 0: return d
    return a.title.compareTo(b.title)
```

**가중치 합산이 아니다.** 두 곡이 처음으로 달라지는 카테고리가 순서를 정하고,
아래 카테고리는 그 위 카테고리가 전부 같은 곡들 사이에서만 순서를 가른다.
`event`를 틀린 것을 `season`을 맞혀서 만회할 수 없다. 예를 들어 `event`가
우선이므로 `precip`를 틀린 크리스마스 곡이 눈 곡보다 위에 온다.

`mood`가 맨 뒤인 것은 의도적이다. bpm은 연속값이라 앞에 두면 그 자체로 순서가
끝나고, 아래 카테고리는 비교될 기회조차 없다. 맨 뒤에 두면 태그 다섯 개가 모두
같은 곡들 사이에서만 방향을 정한다.

### SQL

```sql
SELECT id, file, title, artist, bpm,

       -- 열거적 필드: 빈 태그가 값 "none"이므로 중립인 경우가 없다
       CASE
         WHEN event IS NULL AND ? = 'none'                    THEN  1
         WHEN event IS NOT NULL
              AND ',' || event || ',' LIKE '%,' || ? || ',%'  THEN  1
         ELSE -1
       END AS event_score,

       CASE
         WHEN precip IS NULL AND ? = 'none'                    THEN  1
         WHEN precip IS NOT NULL
              AND ',' || precip || ',' LIKE '%,' || ? || ',%'  THEN  1
         ELSE -1
       END AS precip_score,

       CASE
         WHEN place IS NULL AND ? = 'none'                     THEN  1
         WHEN place IS NOT NULL
              AND ',' || place || ',' LIKE '%,' || ? || ',%'   THEN  1
         ELSE -1
       END AS place_score,

       -- time, season: 빈 태그는 모름이므로 중립
       CASE
         WHEN time IS NULL                                      THEN  0
         WHEN ',' || time || ',' LIKE '%,' || ? || ',%'         THEN  1
         ELSE -1
       END AS time_score,

       CASE
         WHEN season IS NULL                                    THEN  0
         WHEN ',' || season || ',' LIKE '%,' || ? || ',%'       THEN  1
         ELSE -1
       END AS season_score

FROM songs
ORDER BY event_score  DESC,
         precip_score DESC,
         place_score  DESC,
         time_score   DESC,
         season_score DESC,
         bpm DESC,          -- happy면 DESC, calm이면 ASC
         title;
```

- 인자는 여덟 개, 순서는 이렇다. `time`, `season`은 `NULL` 분기에 파라미터가
  없어서 한 번만 쓴다.

  ```
  event, event, precip, precip, place, place, time, season
  ```

- 정렬 방향은 파라미터로 넣을 수 없다. `ORDER BY bpm ?`는 문법 오류다.
  `DESC` 아니면 `ASC`를 SQL 문자열에 넣어 실행한다. 둘뿐인 값이라 이스케이프할
  것이 없다.
- `mood`는 SQL에 이 방향으로만 등장한다.
- 값은 닫힌 열거형(`tag.py`의 `ALLOWED`)이라 `%`나 `_`가 들어갈 수 없다.
  그래도 쿼리에 이어 붙이는 것보다 바인딩하는 편이 낫다.

## AAOS Kotlin 예제

`tags.db`를 `app/src/main/assets/tags.db`에 넣고, 첫 실행 때 앱 DB 경로로
복사해서 연다.

```kotlin
fun openTagsDb(context: Context): SQLiteDatabase {
    val target = context.getDatabasePath("tags.db")
    if (!target.exists()) {
        target.parentFile?.mkdirs()
        context.assets.open("tags.db").use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
    }
    return SQLiteDatabase.openDatabase(
        target.path, null, SQLiteDatabase.OPEN_READONLY
    )
}
```

쿼리는 위 SQL 그대로다. 별칭은 `ORDER BY`에서 바로 쓸 수 있으므로 서브쿼리로
감쌀 필요가 없다.

```kotlin
private val SCORES = """
    CASE WHEN event IS NULL AND ? = 'none' THEN 1
         WHEN event IS NOT NULL AND (',' || event || ',') LIKE ('%,' || ? || ',%') THEN 1
         ELSE -1 END AS event_score,
    CASE WHEN precip IS NULL AND ? = 'none' THEN 1
         WHEN precip IS NOT NULL AND (',' || precip || ',') LIKE ('%,' || ? || ',%') THEN 1
         ELSE -1 END AS precip_score,
    CASE WHEN place IS NULL AND ? = 'none' THEN 1
         WHEN place IS NOT NULL AND (',' || place || ',') LIKE ('%,' || ? || ',%') THEN 1
         ELSE -1 END AS place_score,
    CASE WHEN time IS NULL THEN 0
         WHEN (',' || time || ',') LIKE ('%,' || ? || ',%') THEN 1
         ELSE -1 END AS time_score,
    CASE WHEN season IS NULL THEN 0
         WHEN (',' || season || ',') LIKE ('%,' || ? || ',%') THEN 1
         ELSE -1 END AS season_score
"""

data class Song(
    val id: String,
    val file: String,
    val title: String?,
    val artist: String?,
    val bpm: Double?,
)

fun rank(db: SQLiteDatabase, state: CarState): List<Song> {
    val dir = if (state.mood == Mood.HAPPY) "DESC" else "ASC"
    val sql = """
        SELECT id, file, title, artist, bpm,
               $SCORES
        FROM songs
        ORDER BY event_score  DESC,
                 precip_score DESC,
                 place_score  DESC,
                 time_score   DESC,
                 season_score DESC,
                 bpm $dir,
                 title
    """.trimIndent()

    val args = arrayOf(
        state.event, state.event,
        state.precip, state.precip,
        state.place, state.place,
        state.time, state.season,
    )

    return db.rawQuery(sql, args).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(Song(
                    id = c.getString(0),
                    file = c.getString(1),
                    title = if (c.isNull(2)) null else c.getString(2),
                    artist = if (c.isNull(3)) null else c.getString(3),
                    bpm = if (c.isNull(4)) null else c.getDouble(4),
                ))
            }
        }
    }
}
```

Room을 쓴다면 파라미터를 이름으로 바인딩할 수 있어서 다섯 개만 넘기면 된다.
정렬 방향은 `-bpm`으로 뒤집어서 한 쿼리로 처리한다.

```kotlin
@Query("""
    SELECT * FROM songs
    ORDER BY
      CASE WHEN event IS NULL AND :event = 'none' THEN 1
           WHEN event IS NOT NULL AND (',' || event || ',') LIKE ('%,' || :event || ',%') THEN 1
           ELSE -1 END DESC,
      CASE WHEN precip IS NULL AND :precip = 'none' THEN 1
           WHEN precip IS NOT NULL AND (',' || precip || ',') LIKE ('%,' || :precip || ',%') THEN 1
           ELSE -1 END DESC,
      CASE WHEN place IS NULL AND :place = 'none' THEN 1
           WHEN place IS NOT NULL AND (',' || place || ',') LIKE ('%,' || :place || ',%') THEN 1
           ELSE -1 END DESC,
      CASE WHEN time IS NULL THEN 0
           WHEN (',' || time || ',') LIKE ('%,' || :time || ',%') THEN 1
           ELSE -1 END DESC,
      CASE WHEN season IS NULL THEN 0
           WHEN (',' || season || ',') LIKE ('%,' || :season || ',%') THEN 1
           ELSE -1 END DESC,
      CASE WHEN :mood = 'happy' THEN -COALESCE(bpm, 0) ELSE COALESCE(bpm, 0) END ASC,
      title
""")
fun rank(event: String, precip: String, place: String,
         time: String, season: String, mood: String): List<SongEntity>
```

Room에 미리 만들어진 DB를 넣을 때:

```kotlin
Room.databaseBuilder(context, AppDb::class.java, "tags.db")
    .createFromAsset("tags.db")
    .build()
```

이 경우 Entity의 컬럼 이름과 타입이 `tags.db`와 정확히 같아야 한다. Room은 첫
실행 때 스키마를 검증한다.

---
SQLite의 `?` 바인딩은 Android 문서 기준이다. `ORDER BY` 별칭 참조와
`CASE`·`||`·`LIKE`는 SQLite 3.18 이상이면 되고 AAOS는 그보다 최신이다.
