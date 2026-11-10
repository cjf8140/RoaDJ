"""audio/ 폴더의 mp3를 읽어 곡 목록을 만든다.

파일명이 '가수 - 제목'이 아닐 수 있으므로 ID3 태그를 먼저 쓰고,
없으면 파일명을 해석하고, 그것도 안 되면 파일명 전체를 제목으로 둔다.
id는 파일명(확장자 제외)이라 파일을 추가·삭제해도 다른 곡의 키가 바뀌지 않는다.
"""
from pathlib import Path

import mutagen

HERE = Path(__file__).parent
AUDIO = HERE / "audio"


def read(path):
    f = mutagen.File(path, easy=True)
    dur = getattr(getattr(f, "info", None), "length", 0.0) or 0.0
    tags = f or {}
    artist = (tags.get("artist") or [""])[0].strip()
    title = (tags.get("title") or [""])[0].strip()

    if not (artist and title):
        head, sep, tail = path.stem.partition(" - ")
        if sep:
            artist = artist or head.strip()
            title = title or tail.strip()
    if not title:
        title = path.stem

    return {"id": path.stem, "file": path.name, "title": title,
            "artist": artist, "dur": round(dur)}


def scan():
    songs = []
    for path in sorted(AUDIO.glob("*.mp3")):
        song = read(path)
        if not song["dur"]:
            print(f"SKIP {path.name} - unreadable audio")
            continue
        songs.append(song)
    return songs


SONGS = scan()
