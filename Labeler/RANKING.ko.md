# 랭킹 규칙

차량의 현재 상태를 곡 순서로 바꾸는 규칙. 앱이 구현할 계약이고,
`rank_test.py`는 같은 규칙을 미리 보여주는 도구다.

English version: [RANKING.md](RANKING.md)

## 상태

모든 카테고리는 항상 값이 있다. 시계가 시간과 계절을 알고 날씨 피드가 강수를
알기 때문에 차는 언제나 값을 보낸다. "지정 안 함" 상태는 없다.

```
state = { event, precip, place, time, season, mood }
```

## 태그 의미

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
되는 것도, 계절을 언급하지 않은 곡이 "계절 없음"이 되는 것도 아니다. 그냥
입을 다문 것이다. 이런 곡은 어떤 요청에도 0점이다.

## 의사코드

### 카테고리 하나의 점수

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

제목 오름차순으로 먼저 정렬                    # 결정적인 기본 순서
그다음 priority를 낮은 것부터 안정 정렬:

    for category in [mood, season, time, place, precip, event]:
        score(song, category) 내림차순 정렬

    if mood == happy: bpm 내림차순 정렬
    else:             bpm 오름차순 정렬
```

사전식 비교와 같다:

```
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
`event`를 틀린 것을 `season`을 맞혀서 만회할 수 없다.

`mood`가 맨 뒤인 것은 의도적이다. bpm은 연속값이라 앞에 두면 그 자체로 순서가
끝나고, 아래 카테고리는 비교될 기회조차 없다. 맨 뒤에 두면 태그 다섯 개가 모두
같은 곡들 사이에서만 방향을 정한다.

## SQL

`tags.db`는 곡 하나가 한 행이고, 태그 필드 하나가 컬럼 하나다. 값이 여러 개면
콤마로 붙여 저장하고(`rain,snow`), `NULL`은 태그 없음을 뜻한다.

```sql
SELECT id, file, title, artist, bpm,

       -- 열거적 필드: 빈 태그가 값 "none"이므로 중립인 경우가 없다
       CASE
         WHEN event IS NULL AND :event = 'none'                       THEN  1
         WHEN event IS NOT NULL
              AND ',' || event || ',' LIKE '%,' || :event || ',%'      THEN  1
         ELSE -1
       END AS event_score,

       CASE
         WHEN precip IS NULL AND :precip = 'none'                     THEN  1
         WHEN precip IS NOT NULL
              AND ',' || precip || ',' LIKE '%,' || :precip || ',%'   THEN  1
         ELSE -1
       END AS precip_score,

       CASE
         WHEN place IS NULL AND :place = 'none'                       THEN  1
         WHEN place IS NOT NULL
              AND ',' || place || ',' LIKE '%,' || :place || ',%'     THEN  1
         ELSE -1
       END AS place_score,

       -- time, season: 빈 태그는 모름이므로 중립
       CASE
         WHEN time IS NULL                                            THEN  0
         WHEN ',' || time || ',' LIKE '%,' || :time || ',%'           THEN  1
         ELSE -1
       END AS time_score,

       CASE
         WHEN season IS NULL                                          THEN  0
         WHEN ',' || season || ',' LIKE '%,' || :season || ',%'       THEN  1
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

주의:

- 저장 값 양옆과 `LIKE` 패턴 양옆의 콤마가 `rain`이 나중에 추가될 `rainy`와
  걸리는 것을 막는다.
- 플레이스홀더가 각각 두 번 나온다. `CASE`가 "태그가 없는가"와 "그 값을
  포함하는가"를 둘 다 보기 때문이다. Android처럼 `?` 위치 바인딩을 쓰면 값
  하나에 두 번씩, 총 열 개를 바인딩해야 한다. named 파라미터를 쓰면 한 번이면
  된다.
- `mood`는 SQL에 `bpm` 방향으로만 등장한다.
- 값은 닫힌 열거형(`tag.py`의 `ALLOWED`)에서 오므로 `%`나 `_`가 들어갈 수 없다.
  그래도 쿼리에 이어 붙이는 것보다 바인딩하는 편이 낫다.

## 예시

상태: `event=none, precip=snow, place=none, time=night, season=winter, mood=happy`

| 순위 | 곡 | bpm | event | precip | place | time | season |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Santa Tell Me | 191.9 | +1 | -1 | +1 | +1 | +1 |
| 2 | 僕のこと | 160.0 | -1 | +1 | +1 | 0 | +1 |
| 3 | ヒロイン | 76.2 | -1 | +1 | -1 | +1 | +1 |
| 4 | Magic | 117.5 | -1 | -1 | +1 | +1 | 0 |
| 5 | 비도 오고 그래서 | 79.2 | -1 | -1 | +1 | +1 | 0 |

1번 곡은 `precip`를 **틀렸는데도** 1위다. `event` 점수가 더 높기 때문이다.
2·3번은 `place`가 나쁜데도 4·5번을 이긴다. `precip`에서 앞서기 때문이다.
우선순위가 의도대로 동작하는 모습이다.

다섯 점수가 모두 같은 곡들 사이에서는 `bpm`이 순서를 정한다. `happy`면
내림차순.

## 미리보기 다시 만들기

```
py -3 run.py         # tags.db 갱신
py -3 rank_test.py   # rank_test.html 생성
```

`rank_test.html`을 브라우저로 열면 된다. 생성물이라 저장소에는 없다.
