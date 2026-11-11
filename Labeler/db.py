"""Export tags.json to SQLite as one wide table so other services can query it.

tags.json stays the source of truth; the database is rebuilt from scratch on
every run, which avoids any chance of the two drifting apart.

Each tag field is a single column holding comma-separated values ("rain,snow"),
or NULL when the song has no tag for that field. Multi-value is rare, so a
column per field beats a join table for the queries we run.
"""
import json
import sqlite3
import sys

from songs import HERE

TAGS = HERE / "tags.json"
DB = HERE / "tags.db"
FIELDS = ("event", "precip", "place", "time", "season")

SCHEMA = """
DROP TABLE IF EXISTS song_tags;
DROP TABLE IF EXISTS songs;
CREATE TABLE songs (
    id           TEXT PRIMARY KEY,
    file         TEXT NOT NULL,
    title        TEXT,
    artist       TEXT,
    lang         TEXT,
    bpm          REAL,
    bpm_source   TEXT,
    energy       REAL,
    valence      REAL,
    danceability REAL,
    model        TEXT,
    event        TEXT,
    precip       TEXT,
    place        TEXT,
    time         TEXT,
    season       TEXT
);
"""


def row(sid, entry):
    tags = entry.get("tags") or {}
    return (sid, entry.get("file"), entry.get("title"), entry.get("artist"),
            entry.get("lang"), entry.get("bpm"), entry.get("bpm_source"),
            entry.get("energy"), entry.get("valence"), entry.get("danceability"),
            entry.get("model"),
            *(",".join(tags.get(f) or []) or None for f in FIELDS))


def main():
    data = json.loads(TAGS.read_text(encoding="utf-8"))
    with sqlite3.connect(DB) as con:
        con.executescript(SCHEMA)
        con.executemany(
            f"INSERT INTO songs VALUES ({','.join('?' * 16)})",
            [row(sid, entry) for sid, entry in data.items()])

    n_tags = sum(len(v) for e in data.values() for v in (e.get("tags") or {}).values())
    print(f"{len(data)} songs, {n_tags} tags -> {DB.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
