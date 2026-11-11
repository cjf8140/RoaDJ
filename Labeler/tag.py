"""lyrics.json의 가사를 DeepSeek에 보내 환경 태그를 받아 tags.json에 저장한다.

태그는 가사에 직접 등장하는 것(explicit)과 의미로 함의된 것(implied)을 구분해서 받는다.
모든 태그에 근거 구절 인용을 요구하고, 허용값 밖의 출력은 버린다.
mood는 가사가 아니라 음악(템포/에너지)에서 나오므로 여기서 판정하지 않는다.
lang은 가사의 문자 종류로 판정한다(LLM 아님).
"""
import json
import os
import re
import sys
import urllib.request

from songs import HERE, SONGS

MODEL = sys.argv[1] if len(sys.argv) > 1 else "deepseek-flash"
OUT = (HERE / "tags.json") if MODEL == "deepseek-flash" else (HERE / f"tags.{MODEL}.json")

# 근거(가사 인용)를 tags.json에 남길지. run.py가 배포용으로 끈다.
KEEP_EVIDENCE = True

ALLOWED = {
    "event": {"christmas"},
    "precip": {"rain", "snow"},
    "place": {"mountain", "sea", "river", "city"},
    "time": {"day", "night"},
    "season": {"spring", "summer", "autumn", "winter"},
}
FIELDS = ("event", "precip", "place", "time", "season")
STRENGTHS = ("explicit", "implied")

KANA = re.compile(r"[\u3040-\u30ff]")     # 히라가나 + 가타카나
HANGUL = re.compile(r"[\uac00-\ud7a3]")


def lang(text):
    """가사의 문자 종류로 곡 언어를 판정한다. 가나는 일본어에만 있다."""
    kana, ko = len(KANA.findall(text)), len(HANGUL.findall(text))
    return "ja" if kana > ko else "ko" if ko else "en"

SYSTEM = """You label songs for an in-car, environment-aware music recommender.
Read the lyrics, infer the situation they describe, and return exactly one JSON object."""

PROMPT = """Tag the lyrics below.

Allowed labels (lowercase, use only these values):
- event: a holiday or anniversary the lyrics refer to. allowed: christmas
- precip: rain or snow falling. allowed: rain, snow
- place: the setting of the lyrics. allowed: mountain, sea, river, city
- time: time of day. allowed: day, night
- season: season. allowed: spring, summer, autumn, winter

Rules:
- A tag counts even when the word is absent, if the lyrics clearly imply it.
  e.g. umbrella / wet street -> rain, stars / streetlights / sleeping street -> night,
       waves / sand -> sea, snow / christmas -> winter, cherry blossoms -> spring,
       autumn leaves -> autumn.
- If there is no evidence at all, leave the array empty. Fame, music videos and
  release dates are not evidence.
- Quote the supporting line verbatim in evidence for every tag.
- strength: explicit when the tag word appears in the lyrics, implied when it is
  only inferred from meaning.
- If a field has more than one tag, give evidence and strength as arrays in the
  same order as the tags.
- Do not judge mood or bpm (those come from the audio).

Output format:
{{"event": [], "precip": [], "place": [], "time": [], "season": [],
 "evidence": {{"event": null, "precip": null, "place": null,
              "time": null, "season": null}},
 "strength": {{"event": null, "precip": null, "place": null,
              "time": null, "season": null}}}}

Song: {artist} - {title}
Lyrics:
{lyrics}"""


def api_key():
    env = HERE / ".env"
    if env.exists():
        for line in env.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))
    key = os.environ.get("DEEPSEEK_API_KEY")
    if not key:
        sys.exit("DEEPSEEK_API_KEY is not set: put it in labeling/.env or the environment")
    return key


def ask(key, song, lyrics):
    body = json.dumps({
        "model": MODEL,
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": PROMPT.format(
                artist=song["artist"], title=song["title"], lyrics=lyrics)},
        ],
        "response_format": {"type": "json_object"},
        "temperature": 0,
        "max_tokens": 3000,
    }).encode()
    req = urllib.request.Request(
        "https://api.deepseek.com/chat/completions", data=body,
        headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        out = json.load(r)
    choice = out["choices"][0]
    if choice.get("finish_reason") == "length":
        raise RuntimeError("max_tokens에서 잘림")
    return json.loads(choice["message"]["content"])


def _as_list(raw, n):
    """Pad evidence/strength to the number of tags, preserving the model's order."""
    if isinstance(raw, list):
        out = [v if isinstance(v, str) else None for v in raw][:n]
    else:
        out = [raw if isinstance(raw, str) else None]
    return out + [None] * (n - len(out))


def clean(raw):
    """Keep only allowed values, and warn when a tag has no evidence or strength."""
    tags, evidence, strength, bad = {}, {}, {}, []
    ev = raw.get("evidence") or {}
    st = raw.get("strength") or {}

    for field in FIELDS:
        vals = [v for v in (raw.get(field) or []) if isinstance(v, str)]
        for v in vals:
            if v not in ALLOWED[field]:
                bad.append(f"invalid {field}={v}")

        ordered = []
        for v in vals:
            if v in ALLOWED[field] and v not in ordered:
                ordered.append(v)

        if not ordered:
            tags[field], evidence[field], strength[field] = [], None, None
            continue

        evs = _as_list(ev.get(field), len(ordered))
        sts = [s if s in STRENGTHS else None
               for s in _as_list(st.get(field), len(ordered))]

        # sort tags together with their evidence/strength so positions stay matched
        triples = sorted(zip(ordered, evs, sts), key=lambda t: t[0])
        tags[field] = [t[0] for t in triples]
        evs = [t[1] for t in triples]
        sts = [t[2] for t in triples]
        evidence[field] = evs[0] if len(evs) == 1 else evs
        strength[field] = sts[0] if len(sts) == 1 else sts

        if not any(evs):
            bad.append(f"{field} has no evidence")
        if not any(sts):
            bad.append(f"{field} has no strength")
    return tags, evidence, strength, bad


def main():
    key = api_key()
    lyrics = json.loads((HERE / "lyrics.json").read_text(encoding="utf-8"))
    data = json.loads(OUT.read_text(encoding="utf-8")) if OUT.exists() else {}
    failed = []

    for song in SONGS:
        if song["id"] in data:
            print(f"SKIP {song['id']:<24} already done")
            continue
        if song["id"] not in lyrics:
            failed.append(song["id"])
            print(f"MISS {song['id']:<24} no lyrics")
            continue

        try:
            tags, evidence, strength, bad = clean(ask(key, song, lyrics[song["id"]]["lyrics"]))
        except Exception as e:
            failed.append(song["id"])
            print(f"FAIL {song['id']:<24} {type(e).__name__}: {str(e)[:100]}")
            continue

        entry = {"file": song["file"],
                 "title": song["title"], "artist": song["artist"],
                 "tags": tags}
        if KEEP_EVIDENCE:
            entry["evidence"] = evidence
        entry.update({"strength": strength, "model": MODEL})
        data[song["id"]] = entry
        flag = "OK  " if not bad else "WARN"
        print(f"{flag} {song['id']:<24} {tags}")
        print(f"     implied: {[f for f, s in strength.items() if s == 'implied']}")
        for b in bad:
            print(f"     bad output - {b}")

    # 이미 태깅된 항목에도 곡 정보를 채워 넣는다 (id만으로는 어떤 곡인지 알 수 없음)
    for song in SONGS:
        if song["id"] not in data:
            continue
        entry = data[song["id"]]
        if not KEEP_EVIDENCE:
            entry.pop("evidence", None)  # 배포용: 가사 인용을 지운다
        data[song["id"]] = {"file": song["file"],
                            "title": song["title"], "artist": song["artist"],
                            "lang": lang(lyrics[song["id"]]["lyrics"])
                                    if song["id"] in lyrics else None,
                            **entry}

    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n{len(data)}/{len(SONGS)} songs -> {OUT.name}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
