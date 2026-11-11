"""Export tags.json to SQLite so other services can query one shared file.

tags.json stays the source of truth; the database is rebuilt from scratch on
every run, which avoids any chance of the two drifting apart.

    songs       one row per track (scalar audio features)
    song_tags   one row per tag: (song_id, field, value, strength)
"""
import json
import sqlite3
import sys

from songs import HERE

TAGS = HERE / "tags.json"
DB = HERE / "tags.db"

SCHEMA = """
DROP TABLE IF EXISTS song_tags;
DROP TABLE IF EXISTS songs;
CREATE TABLE songs (
    id           TEXT PRIMARY KEY,
    file         TEXT NOT NULL,
    title        TEXT,
    artist       TEXT,
    bpm          REAL,
    bpm_source   TEXT,
    energy       REAL,
    valence      REAL,
    danceability REAL,
    model        TEXT
);
CREATE TABLE song_tags (
    song_id  TEXT NOT NULL REFERENCES songs(id) ON DELETE CASCADE,
    field    TEXT NOT NULL,
    value    TEXT NOT NULL,
    strength TEXT,
    PRIMARY KEY (song_id, field, value)
);
CREATE INDEX idx_song_tags_lookup ON song_tags(field, value);
"""


def tag_rows(entry):
    tags = entry.get("tags") or {}
    strength = entry.get("strength") or {}
    for field, values in tags.items():
        s = strength.get(field)
        for i, value in enumerate(values):
            st = s[i] if isinstance(s, list) and i < len(s) else s
            yield field, value, st if isinstance(st, str) else None


def main():
    data = json.loads(TAGS.read_text(encoding="utf-8"))
    with sqlite3.connect(DB) as con:
        con.executescript(SCHEMA)
        for sid, e in data.items():
            con.execute(
                "INSERT INTO songs VALUES (?,?,?,?,?,?,?,?,?,?)",
                (sid, e.get("file"), e.get("title"), e.get("artist"),
                 e.get("bpm"), e.get("bpm_source"), e.get("energy"),
                 e.get("valence"), e.get("danceability"), e.get("model")))
            con.executemany(
                "INSERT INTO song_tags VALUES (?,?,?,?)",
                [(sid, f, v, s) for f, v, s in tag_rows(e)])

    n_tags = sum(len(v) for e in data.values() for v in (e.get("tags") or {}).values())
    print(f"{len(data)} songs, {n_tags} tags -> {DB.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
