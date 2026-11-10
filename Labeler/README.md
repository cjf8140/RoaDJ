# Music Labeling Pipeline

Turns a folder of MP3 files into environment tags and audio features for an
in-car recommendation mockup. Everything runs offline on your machine; the only
external calls are to public APIs.

## Pipeline

```
audio/*.mp3
   │  songs.py      read ID3 tags or parse "Artist - Title" from the filename
   ▼
lyrics.py            fetch lyrics from LRCLIB
   │
   ▼
tag.py               DeepSeek infers event / precip / place / time / season
   │
   ▼
audio_api.py         ReccoBeats returns bpm, energy, valence, danceability
   │                 (database lookup first, 30s file analysis as fallback)
   ▼
bpm.py               local librosa estimate, only if both API paths fail
   │
   ▼
tags.json
```

Every stage skips songs that are already processed, so the pipeline is safe to
re-run. Failures are reported at the end instead of being hidden.

## Requirements

- Python 3.11 or newer (tested on 3.14)
- No separate ffmpeg install: `imageio-ffmpeg` ships a binary

```bash
py -3 -m pip install -r requirements.txt
```

## Setup

1. Create `.env` in this folder:

   ```
   DEEPSEEK_API_KEY=sk-...
   ```

2. Put your MP3 files in `audio/`.

## Run

shell:

```bash
python run.py
```

## Configuration

Two flags at the top of `run.py` control what ends up in the output:

| Flag | Default | Meaning |
| --- | --- | --- |
| `STORE_LYRICS` | `False` | Keep `lyrics.json` (full lyrics text) |
| `STORE_EVIDENCE` | `False` | Keep the quoted lyric line behind each tag |

Defaults are the distribution-safe values: no copyrighted text is written.
Set them to `True` while debugging. Note that `STORE_LYRICS = False` means
lyrics are fetched again on every run (a few seconds for a small library);
tags stay cached, so no extra LLM calls are made.

## Output

`tags.json`, keyed by the file name without extension:

```json
"Mrs. GREEN APPLE - Lilac": {
  "file": "Mrs. GREEN APPLE - Lilac.mp3",
  "title": "Lilac",
  "artist": "Mrs. GREEN APPLE",
  "tags": {
    "event": [],
    "precip": ["rain"],
    "place": [],
    "time": ["day", "night"],
    "season": ["spring", "summer"]
  },
  "strength": {
    "event": null,
    "precip": "explicit",
    "place": null,
    "time": null,
    "season": null
  },
  "model": "deepseek-flash",
  "bpm": 92.4,
  "bpm_source": "analysis",
  "energy": 0.812,
  "valence": 0.544,
  "danceability": 0.432
}
```

- `tags` uses only the allowed values; an empty array means "not found", not "no".
- `strength` marks whether the tag word appears in the lyrics (`explicit`) or
  was inferred from meaning (`implied`). When there are multiple tag in one category, strength can be null.
- `bpm_source` is `reccobeats` (database), `analysis` (uploaded 30s clip) or
  `librosa` (local estimate).

## Files

| File | Role |
| --- | --- |
| `run.py` | Entry point; runs all stages and prints a summary |
| `songs.py` | Scans `audio/` and resolves artist, title, duration |
| `lyrics.py` | LRCLIB lookup with duration check and iTunes fallback |
| `tag.py` | DeepSeek prompt, enum validation, `tags.json` output |
| `audio_api.py` | ReccoBeats lookup / file analysis, `bpm.py` fallback hook |
| `bpm.py` | Local librosa tempo estimate (last resort) |
| `itunes.py` | Resolves official title/artist for non-Latin releases |
| `energy.py` | Legacy local onset/RMS metrics, not part of the pipeline |


## Metadata resolution

`songs.py` fills artist and title in this order:

1. ID3 `artist` / `title` tags
2. `Artist - Title` parsed from the file name
3. the whole file name used as the title, artist left empty

When the artist is unknown, lyrics are searched by title only and BPM comes from
the local estimate, because a title-only API match often returns a cover.

## External services

| Service | Key | Used for |
| --- | --- | --- |
| [LRCLIB](https://lrclib.net) | none | lyrics |
| [DeepSeek](https://deepseek.com) | `DEEPSEEK_API_KEY` | tagging |
| [ReccoBeats](https://reccobeats.com) | none | bpm and audio features |
| [iTunes Search](https://performance-partners.apple.com/search-api) | none | official title/artist |

Rate limits are handled with a one-second delay per request and a retry on
HTTP 429.

## Limitations

- Lyrics and audio features are copyrighted or third-party data. The default
  configuration writes neither lyrics nor quoted evidence, so the output holds
  only factual tags and measurements.
- ReccoBeats' 30s analysis accepts stereo only; mono clips are rejected with 400.
- An MP3 file name is the song key. Renaming a file makes it a new song, so it
  is processed again.

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| `UnicodeEncodeError` in the console | `chcp 65001` and `set PYTHONIOENCODING=utf-8` |
| `MISS ... no lyrics` | Artist/title mismatch; rename to `Artist - Title` or add ID3 tags |
| `MISS ... lookup/analysis failed` | Song not in ReccoBeats and upload failed; `bpm.py` fills bpm locally |
| `SKIP ... unreadable audio` | File is not a readable audio stream |
| Blank tag arrays | The lyrics genuinely contain no environment cues |
