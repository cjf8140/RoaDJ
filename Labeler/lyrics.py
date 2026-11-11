"""LRCLIB에서 가사를 받아 lyrics.json에 모은다. mp3는 건드리지 않는다.

이미 수집된 곡은 건너뛴다. 다시 받으려면 lyrics.json에서 해당 항목을 지우면 된다.
"""
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

import itunes
from songs import HERE, SONGS

UA = {"User-Agent": "pleos-mockup/0.1 (labeling)"}
DUR_TOLERANCE = 3
OUT = HERE / "lyrics.json"


def api(path, **params):
    url = "https://lrclib.net/api/" + path + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers=UA)
    try:
        return json.load(urllib.request.urlopen(req, timeout=20))
    except OSError as e:            # HTTPError, timeout, DNS, connection reset
        return {"error": getattr(e, "code", str(e))}


def has_lyrics(rec):
    return isinstance(rec, dict) and "error" not in rec and not rec.get("instrumental") \
        and bool(rec.get("plainLyrics") or rec.get("syncedLyrics"))


def _search(song, title, artist):
    if artist:
        rec = api("get", artist_name=artist, track_name=title, duration=song["dur"])
        if has_lyrics(rec) and abs(rec["duration"] - song["dur"]) <= DUR_TOLERANCE:
            return rec, "get"

    # 가수를 모르면 제목만으로 검색한다 (파일명이 '가수 - 제목'이 아닌 경우)
    params = {"track_name": title, **({"artist_name": artist} if artist else {})}
    hits = api("search", **params)
    cands = [h for h in hits if has_lyrics(h)
             and abs(h["duration"] - song["dur"]) <= DUR_TOLERANCE] \
        if isinstance(hits, list) else []
    if cands:
        return min(cands, key=lambda h: abs(h["duration"] - song["dur"])), "search"
    return None, None


def find(song):
    """정확 매치가 instrumental 플레이스홀더일 수 있어서, 실패하면 검색 결과에서 고른다.
    아티스트는 한글/로마자 표기가 갈리므로 실패하면 iTunes 공식 표기로 다시 찾는다."""
    rec, how = _search(song, song["title"], song["artist"])
    if rec:
        return rec, how
    if not song["artist"]:
        return None, "no artist info - title-only lookup failed"
    alt = itunes.lookup(song["title"], song["artist"], song["dur"])
    if alt:
        rec, how = _search(song, alt["title"], alt["artist"])
        if rec:
            return rec, how
    return None, "no matching record"


def to_plain(rec):
    if rec.get("plainLyrics"):
        return rec["plainLyrics"].strip()
    # synced만 있으면 타임스탬프만 걷어낸다
    return "\n".join(re.sub(r"^\[\d+:\d+(?:[.:]\d+)?\]", "", ln)
                     for ln in rec["syncedLyrics"].splitlines()).strip()


def main():
    data = json.loads(OUT.read_text(encoding="utf-8")) if OUT.exists() else {}
    failed = []
    for song in SONGS:
        if song["id"] in data:
            print(f"SKIP {song['id']:<24} already done")
            continue

        rec, how = find(song)
        if not rec:
            failed.append((song["id"], how))
            print(f"MISS {song['id']:<24} {how}")
            continue

        text = to_plain(rec)
        data[song["id"]] = {
            "title": song["title"],
            "artist": song["artist"],
            "duration": rec["duration"],
            "lrclib_id": rec["id"],
            "lyrics": text,
        }
        print(f"OK   {song['id']:<24} {how:<6} {len(text):5} chars")

    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")

    # 쓰고 나서 원문 대조
    saved = json.loads(OUT.read_text(encoding="utf-8"))
    for sid, entry in saved.items():
        assert entry["lyrics"].strip(), f"{sid}: empty lyrics"
    print(f"\n{len(saved)}/{len(SONGS)} songs -> {OUT.name}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
