"""onset rate와 RMS를 계산해 tags.json에 기록한다.

bpm은 옥타브 오류(86 vs 172)가 있어 mood 판단에 부적합하다. 둘 다 템포와 무관한 에너지 지표다.
"""
import json
import sys

import librosa
import numpy as np

from songs import AUDIO, HERE, SONGS

TAGS = HERE / "tags.json"


def features(path):
    y, sr = librosa.load(path, sr=22050, mono=True)
    dur = len(y) / sr
    onsets = librosa.onset.onset_detect(y=y, sr=sr, units="time", backtrack=True)
    rms = float(librosa.feature.rms(y=y).mean())
    return round(len(onsets) / dur, 2), round(20 * np.log10(max(rms, 1e-9)), 1)


def main():
    data = json.loads(TAGS.read_text(encoding="utf-8"))
    for song in SONGS:
        entry = data.get(song["id"])
        if not entry:
            print(f"MISS {song['id']:<24} no tags")
            continue
        if "onset_rate" in entry:
            print(f"SKIP {song['id']:<24} {entry['onset_rate']}")
            continue

        rate, rms_db = features(AUDIO / song["file"])
        assert 0 < rate < 20, f"{song['id']}: onset_rate out of range {rate}"
        assert -60 < rms_db < 0, f"{song['id']}: rms out of range {rms_db}"
        entry["onset_rate"], entry["rms_db"] = rate, rms_db
        print(f"OK   {song['id']:<24} onset {rate:5.2f}/s  rms {rms_db:6.1f}dB  "
              f"bpm {entry.get('bpm')}")

    TAGS.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\nenergy {sum('onset_rate' in e for e in data.values())}/{len(SONGS)} songs -> {TAGS.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
