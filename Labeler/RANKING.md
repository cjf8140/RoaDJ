# Ranking rules

How a service turns the current car state into a song order. This is the
contract the app implements; `rank_test.py` is a preview of the same rules.

## State

Every category is always set. The car always sends a value (the clock knows
time and season, the weather feed knows precipitation), so there is no
"unset" case.

```
state = { event, precip, place, time, season, mood }
```

## Tag semantics

| Category | Values | Missing tag means | Score range |
| --- | --- | --- | --- |
| `event` | `none`, `christmas` | `none` (no event) | +1 / -1 |
| `precip` | `none`, `rain`, `snow` | `none` (clear) | +1 / -1 |
| `place` | `none`, `mountain`, `sea`, `river`, `city` | `none` (no particular place) | +1 / -1 |
| `time` | `day`, `night` | unknown | +1 / 0 / -1 |
| `season` | `spring`, `summer`, `autumn`, `winter` | unknown | +1 / 0 / -1 |
| `mood` | `happy`, `calm` | no tag | sort direction |

`event`, `precip` and `place` are exhaustive: a song without the tag really is
"no event", "clear" or "no particular place", so a missing tag is compared like
any other value.

`time` and `season` are not exhaustive: a song that never names a time of day or
a season is not a "daytime" or "non-seasonal" song, it is simply silent on the
subject. A silent song scores 0 against every request.

## Pseudocode

### Score of one category

```
score(song, category):
    if category == mood:
        return nothing                 # mood only sets the bpm direction

    values  = song.tags[category]      # list, may be empty
    request = state[category]

    if values is empty:
        if category in (event, precip, place):
            return +1 if request == "none" else -1
        return 0                        # time, season: unknown, neutral

    if request == "none":
        return -1                       # the song claims a value where none was wanted

    return +1 if request in values else -1
```

### Order

```
priority = [event, precip, place, time, season]     # highest first

sort songs ascending by title                       # deterministic base order
then stable-sort by priority, highest priority outermost:

    for category in [mood, season, time, place, precip, event]:
        sort by score(song, category) descending

    if mood == happy: sort by bpm descending
    else:             sort by bpm ascending
```

Equivalent to a lexicographic comparison:

```
compare(a, b):
    for category in priority:
        d = score(b, category) - score(a, category)
        if d != 0: return d
    d = (a.bpm - b.bpm) * (mood == happy ? -1 : 1)
    if d != 0: return d
    return a.title.compareTo(b.title)
```

This is **not** a weighted sum. The first category where two songs differ
decides; a lower category only breaks ties among songs equal on every higher
one. A wrong value in `event` cannot be compensated by a match in `season`.

`mood` sits last on purpose. BPM is continuous, so if it came earlier it would
settle the order on its own and every category below it would never be
compared. At the end it only orders songs that already tie on all five tags.

## SQL

`tags.db` has one row per song and one column per tag field. Multiple values are
stored comma-separated (`rain,snow`) and `NULL` means no tag.

```sql
SELECT id, file, title, artist, bpm,

       -- exhaustive fields: missing tag is the value "none", so there is no neutral case
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

       -- time and season: missing tag is unknown, so it scores neutral
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
         bpm DESC,          -- DESC for happy, ASC for calm
         title;
```

Notes:

- The commas around the stored value and in the `LIKE` pattern prevent `rain`
  from matching a future value such as `rainy`.
- Every placeholder appears twice, because each `CASE` tests both "is the tag
  missing" and "does it contain the value". With positional `?` binding in
  Android, bind each value twice (ten arguments); named parameters avoid that.
- `mood` appears in the SQL only as the `bpm` direction.
- The values come from a closed enum (`ALLOWED` in `tag.py`), so they contain no
  `%` or `_`. Binding them is still preferable to concatenating them into the
  query.

## Worked example

State: `event=none, precip=snow, place=none, time=night, season=winter, mood=happy`

| # | song | bpm | event | precip | place | time | season |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Santa Tell Me | 191.9 | +1 | -1 | +1 | +1 | +1 |
| 2 | 僕のこと | 160.0 | -1 | +1 | +1 | 0 | +1 |
| 3 | ヒロイン | 76.2 | -1 | +1 | -1 | +1 | +1 |
| 4 | Magic | 117.5 | -1 | -1 | +1 | +1 | 0 |
| 5 | 비도 오고 그래서 | 79.2 | -1 | -1 | +1 | +1 | 0 |

Song 1 wins with a **wrong** `precip` because its `event` score is higher.
Songs 2 and 3 beat songs 4 and 5 on `precip` despite a worse `place`. That is
the priority order working as intended.

Within a group where all five scores are equal, `bpm` decides: for `happy`,
descending.

## Regenerating the preview

```
py -3 run.py         # refreshes tags.db
py -3 rank_test.py   # writes rank_test.html
```

Open `rank_test.html` in a browser. `rank_test.html` is generated and not
committed.
