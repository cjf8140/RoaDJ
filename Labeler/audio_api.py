"""ReccoBeats(무료, 키 없음)에서 tempo/energy/valence/danceability를 받아 tags.json에 넣는다.

Spotify Audio Features가 폐지된 뒤의 대체 API. 검색은 제목만 되므로 제목으로 찾고
아티스트·길이로 걸러낸다. DB에 없으면 오디오 분석 엔드포인트에 30초 클립을 올려
같은 피처를 뽑고, 그것도 실패하면 bpm.py의 로컬 추정에 맡긴다.
"""
import json
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

import imageio_ffmpeg

import itunes
from songs import AUDIO, HERE, SONGS

BASE = "https://api.reccobeats.com"
UA = {"User-Agent": "pleos-mockup/0.1 (labeling)"}
TAGS = HERE / "tags.json"
MAX_PAGES = 8
MAX_ARTIST_PAGES = 20
ANALYSIS = "/v1/analysis/audio-features"


def get(path, **params):
    url = BASE + path + ("?" + urllib.parse.urlencode(params) if params else "")
    for attempt in range(4):
        time.sleep(1.0)  # 429 방지
        try:
            return json.load(urllib.request.urlopen(
                urllib.request.Request(url, headers=UA), timeout=25))
        except urllib.error.HTTPError as e:
            if e.code == 429 and attempt < 3:
                time.sleep(5 * (attempt + 1))
                continue
            if e.code != 429 and 400 <= e.code < 500:
                return {}          # API가 이 곡/가수를 모른다. 파일 분석으로 넘긴다
            raise


def _search(song, query, artist):
    """제목으로 검색 후 아티스트 + 길이(±10초)로 원곡을 고른다. 제목이 정확히 같은 쪽 우선."""
    want = song["dur"] * 1000
    name = artist.lower()
    best = None
    for page in range(MAX_PAGES):
        r = get("/v1/track/search", searchText=query, size=25, page=page)
        for c in r.get("content") or []:
            artists = " / ".join(a["name"] for a in c.get("artists", [])).lower()
            dur = c.get("durationMs") or 0
            if not dur or abs(dur - want) > 10_000:
                continue
            if name not in artists:
                continue
            key = (0 if _key(c["trackTitle"]) == _key(query) else 1, abs(dur - want))
            if best is None or key < best[0]:
                best = (key, c)
        if page + 1 >= (r.get("totalPages") or 1):
            break
    return best[1] if best else None


def artist_id(name):
    """아티스트 이름으로 ReccoBeats id를 찾는다. 정확히 같은 이름만 인정."""
    r = get("/v1/artist/search", searchText=name, size=10)
    for a in r.get("content") or []:
        if a["name"].lower() == name.lower():
            return a["id"]
    return None


def _key(title):
    """제목 비교용. 끝의 마침표/공백 차이 때문에 다른 곡으로 넘어가는 걸 막는다 (lulu vs lulu.)."""
    return title.strip().lower().rstrip(" .!?。！？")


def _artist_track(aid, song, title):
    """아티스트 카탈로그에서 제목이 같은 트랙을 찾는다.

    제목 검색은 결과가 200건에서 잘려서 유명한 아티스트의 트랙이 안 나오는 경우가 있다.
    뮤직비디오는 인트로/아웃트로 때문에 원곡보다 길어서 길이 허용치를 넓게 잡는다 (ケセラセラ: 314s vs 272s).
    """
    want = song["dur"] * 1000
    best = None
    for page in range(MAX_ARTIST_PAGES):
        r = get(f"/v1/artist/{aid}/track", size=25, page=page)
        for c in r.get("content") or []:
            if _key(c["trackTitle"]) != _key(title):
                continue
            diff = abs((c.get("durationMs") or 0) - want)
            if diff > 60_000:
                continue
            if best is None or diff < best[0]:
                best = (diff, c)
        if page + 1 >= (r.get("totalPages") or 1):
            break
    return best[1] if best else None


def find(song):
    if not song["artist"]:
        return None  # 가수를 모르면 엉뚱한 곡을 집을 수 있어 BPM은 로컬 계산에 맡긴다

    tries = [(song["title"], song["artist"])]
    alt = itunes.lookup(song["title"], song["artist"], song["dur"])
    if alt:
        tries.append((alt["title"], alt["artist"]))

    # 제목이 정확히 같은 트랙만 인정한다. 아니면 다른 곡 피처를 붙이느니 파일 분석으로 넘긴다.
    for title, artist in tries:
        hit = _search(song, title, artist)
        if hit and _key(hit["trackTitle"]) == _key(title):
            return hit
        aid = artist_id(artist)
        exact = _artist_track(aid, song, title) if aid else None
        if exact:
            return exact
    return None


def analyze(song):
    """DB에 없는 곡은 파일에서 30초를 잘라 올려 피처를 뽑는다 (API 제한: 5MB, 30초).
    모노 트랙은 400으로 거부당하므로 스테레오로 자른다."""
    src = AUDIO / song["file"]
    start = max(0, min(30, song["dur"] // 4))  # 인트로를 피해 중간에서 자른다
    clip = Path(tempfile.gettempdir()) / f"pleos-{uuid.uuid4().hex}.mp3"
    try:
        subprocess.run(
            [imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-v", "quiet",
             "-ss", str(start), "-i", str(src), "-t", "30",
             "-ac", "2", "-ar", "44100", "-b:a", "192k", str(clip)],
            check=True, timeout=120)
        data = clip.read_bytes()
    finally:
        clip.unlink(missing_ok=True)

    boundary = uuid.uuid4().hex
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="audioFile"; filename="clip.mp3"\r\n'
        f"Content-Type: audio/mpeg\r\n\r\n"
    ).encode() + data + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(
        BASE + ANALYSIS, data=body,
        headers={**UA, "Content-Type": f"multipart/form-data; boundary={boundary}"})
    time.sleep(1.0)
    return json.load(urllib.request.urlopen(req, timeout=120))


def main():
    data = json.loads(TAGS.read_text(encoding="utf-8"))
    failed = []
    for song in SONGS:
        entry = data.get(song["id"])
        if not entry:
            print(f"MISS {song['id']:<24} no tags")
            failed.append(song["id"])
            continue
        if "bpm" in entry:
            print(f"SKIP {song['id']:<24} already done")
            continue

        hit = find(song)
        if hit:
            f = get(f"/v1/track/{hit['id']}/audio-features")
            source, label = "reccobeats", hit["trackTitle"][:24]
        else:
            try:
                f = analyze(song)
            except Exception as e:
                entry["bpm_source"] = "librosa"
                print(f"MISS {song['id']:<24} lookup/analysis failed ({type(e).__name__}) "
                      f"-> bpm.py will estimate locally")
                failed.append(song["id"])
                continue
            source, label = "analysis", "file analysis (30s)"

        entry.update({
            "bpm": round(f["tempo"], 1),
            "bpm_source": source,
            "energy": round(f["energy"], 3),
            "valence": round(f["valence"], 3),
            "danceability": round(f["danceability"], 3),
        })
        entry.pop("onset_rate", None)  # mood 대용으로 부적합했던 로컬 지표
        entry.pop("rms_db", None)
        print(f"OK   {song['id']:<24} {label:<26} "
              f"tempo {f['tempo']:6.1f}  energy {f['energy']:.2f}  valence {f['valence']:.2f}")

    TAGS.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    api = sum(e.get("bpm_source") in ("reccobeats", "analysis") for e in data.values())
    print(f"\nbpm {api}/{len(SONGS)} songs from API"
          f" (lookup {sum(e.get('bpm_source') == 'reccobeats' for e in data.values())}, "
          f"analysis {sum(e.get('bpm_source') == 'analysis' for e in data.values())})")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
