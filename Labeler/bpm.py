"""각 mp3의 BPM을 추정해 tags.json에 bpm 숫자로 기록한다.

mood(신남/안신남)는 여기서 판정하지 않는다 — 앱이 bpm으로 판단한다.
"""
import json
import sys

import librosa
import numpy as np

from songs import AUDIO, HERE, SONGS

TAGS = HERE / "tags.json"


def estimate(path):
    y, sr = librosa.load(path, sr=22050, mono=True)
    tempo, _ = librosa.beat.beat_track(y=y, sr=sr)
    # beat_track은 half/double tempo를 섞어 낸다 (예: 171 → 86). 원값을 그대로 쓴다.
    return round(float(np.atleast_1d(tempo)[0]), 1)


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
            print(f"SKIP {song['id']:<24} {entry['bpm']}")
            continue
        try:
            bpm = estimate(AUDIO / song["file"])
        except Exception as e:
            print(f"FAIL {song['id']:<24} {type(e).__name__}: {str(e)[:100]}")
            failed.append(song["id"])
            continue
        assert 20 < bpm < 300, f"{song['id']}: implausible bpm {bpm}"
        entry["bpm"] = bpm
        print(f"OK   {song['id']:<24} {bpm}")

    TAGS.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\nbpm {sum('bpm' in e for e in data.values())}/{len(SONGS)} songs -> {TAGS.name}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
