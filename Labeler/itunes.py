"""iTunes Search API로 공식 제목/아티스트를 조회한다 (키 불필요).

한국어·일본어 곡이 Spotify/LRCLIB에 영어/로마자로 등록된 경우를 자동 보정하는 용도다.
실패한 단계에서만 호출한다.
"""
import json
import re
import urllib.parse
import urllib.request

UA = {"User-Agent": "pleos-mockup/0.1 (labeling)"}

# iTunes는 "Song (feat. X)" 형태로 주는데 검색 API에서는 이 꼬리표가 방해된다
FEAT = re.compile(r"\s*[\(\[][^)\]]*\b(feat|ft|with)\.?\b[^)\]]*[\)\]]", re.I)


def lookup(title, artist, dur):
    q = urllib.parse.urlencode({"term": f"{artist} {title}", "entity": "song", "limit": 10})
    try:
        with urllib.request.urlopen(
                urllib.request.Request("https://itunes.apple.com/search?" + q, headers=UA),
                timeout=15) as r:
            results = json.load(r).get("results", [])
    except Exception:
        return None

    for x in results:
        if abs((x.get("trackTimeMillis") or 0) / 1000 - dur) <= 15:
            return {"title": FEAT.sub("", x["trackName"]).strip(), "artist": x["artistName"]}
    return None
