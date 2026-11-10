"""전체 파이프라인: audio/의 mp3 → 가사 → 태깅 → BPM/오디오 피처.

mp3는 사용자가 준비해 audio/에 넣는다 (유튜브 다운로드는 tools/의 별도 도구).
각 단계는 이미 처리된 곡을 건너뛰므로 몇 번을 돌려도 안전하다.
실패한 곡은 숨기지 않고 마지막에 리포트한다.

기본값은 배포용이다 — 가사 원문(lyrics.json)과 근거 인용(evidence)을 남기지 않는다.
디버깅할 때 아래 두 상수를 True로 올린다. 단, STORE_LYRICS를 끄면 매 실행마다
LRCLIB에서 가사를 다시 받는다 (태그는 tags.json에 캐시되므로 재호출은 없음).
"""
import json
import sys

# Windows 콘솔이 cp949면 일본어 제목 출력에서 UnicodeEncodeError로 죽는다.
# 깨져 보이더라도 멈추지 않게 하고, 제대로 보려면 chcp 65001 + PYTHONIOENCODING=utf-8
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(errors="replace")
    sys.stderr.reconfigure(errors="replace")

import audio_api
import bpm
import lyrics
import tag
from songs import HERE, SONGS

# 배포물에 남기지 않을 것들. 디버깅할 때만 True로 바꾼다.
STORE_LYRICS = False    # lyrics.json (가사 원문)
STORE_EVIDENCE = False  # tags.json의 evidence (가사 인용)

STEPS = (lyrics, tag, audio_api, bpm)


def main():
    tag.KEEP_EVIDENCE = STORE_EVIDENCE
    print(f"lyrics: {'on' if STORE_LYRICS else 'off'} | "
          f"evidence: {'on' if STORE_EVIDENCE else 'off'}\n")

    for step in STEPS:
        if step.main():
            print(f"[{step.__name__}] some items failed - see log above\n")

    if not STORE_LYRICS:
        lyrics.OUT.unlink(missing_ok=True)  # 가사는 중간 재료일 뿐, 남기지 않는다

    tags = json.loads((HERE / "tags.json").read_text(encoding="utf-8"))
    incomplete = []
    print(f"{'id':<40} {'bpm':>6} {'energy':>7}  tags")
    for song in SONGS:
        e = tags.get(song["id"], {})
        bpm = e.get("bpm")
        energy = e.get("energy")
        labels = ",".join(f"{k}:{'/'.join(v)}" for k, v in e.get("tags", {}).items() if v)
        if bpm is None or not e.get("tags"):
            incomplete.append(song["id"])
        print(f"{song['id']:<40} {bpm if bpm is not None else '-':>6} "
              f"{energy if energy is not None else '-':>7}  {labels}")

    print(f"\nDone {len(SONGS) - len(incomplete)}/{len(SONGS)}"
          + (f" | incomplete: {incomplete}" if incomplete else ""))
    return 1 if incomplete else 0


if __name__ == "__main__":
    sys.exit(main())
