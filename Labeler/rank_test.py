"""Build rank_test.html from tags.db so the ranking order can be eyeballed.

Priority is fixed top to bottom and the order is lexicographic: the first
category where two songs differ decides. A category left at "-" has no effect,
so an unspecified field needs no special handling.

    py -3 rank_test.py
"""
import json
import sqlite3
import sys

from songs import HERE

DB = HERE / "tags.db"
OUT = HERE / "rank_test.html"
FIELDS = ("event", "precip", "place", "time", "season")

TEMPLATE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Ranking test - Labeler</title>
<style>
  :root { color-scheme: light dark; }
  body { font: 14px/1.45 system-ui, sans-serif; margin: 24px; max-width: 1100px; }
  h1 { font-size: 18px; margin: 0 0 4px; }
  .hint { color: #888; margin: 0 0 16px; }
  table { border-collapse: collapse; width: 100%; }
  th, td { padding: 5px 8px; border-bottom: 1px solid #d8d8d8; text-align: left; }
  th { font-weight: 600; color: #666; font-size: 12px; text-transform: uppercase; }
  select, input { font: inherit; }
  .pos { background: #d9f2d9; }
  .neg { background: #f7d9d9; }
  .zero { color: #999; }
  .num { text-align: right; font-variant-numeric: tabular-nums; }
  .tags { color: #777; font-size: 12px; }
  #summary { margin: 14px 0; color: #555; font-family: ui-monospace, monospace; }
  tbody tr:hover { background: rgba(128,128,128,.12); }
</style>
</head>
<body>
<h1>Ranking test</h1>
<p class="hint">Top to bottom is priority. +1 = tag matches, 0 = song has no tag
for that field, -1 = song has a different tag. Unspecified category ("-") is skipped.</p>
<table id="controls"><tbody></tbody></table>
<p id="summary"></p>
<table id="result"><thead></thead><tbody></tbody></table>
<script>
const SONGS = __SONGS__;

const FIELDS = [
  { key: "event",  values: ["christmas"] },
  { key: "precip", values: ["rain", "snow"] },
  { key: "place",  values: ["mountain", "sea", "river", "city"] },
  { key: "time",   values: ["day", "night"] },
  { key: "mood",   values: ["happy", "calm"] },
  { key: "season", values: ["spring", "summer", "autumn", "winter"] },
];

const state = { moodCut: 120 };
const esc = s => String(s).replace(/[&<>"]/g,
  c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

const controls = document.querySelector("#controls tbody");
const thead = document.querySelector("#result thead");
const tbody = document.querySelector("#result tbody");
const summary = document.querySelector("#summary");

function scoreOf(song, key) {
  const req = state[key];
  if (!req) return 0;
  if (key === "mood") {
    if (song.bpm == null) return 0;
    return ((song.bpm >= state.moodCut) === (req === "happy")) ? 1 : -1;
  }
  const have = song[key] || [];
  if (have.length === 0) return 0;
  if (req === "none") return -1;
  return have.includes(req) ? 1 : -1;
}

function active() { return FIELDS.filter(f => state[f.key]); }

function buildControls() {
  controls.innerHTML = "";
  FIELDS.forEach((f, i) => {
    const tr = document.createElement("tr");
    const opts = ['<option value="">-</option>']
      .concat(f.values.map(v => `<option value="${v}">${v}</option>`))
      .concat(f.key === "mood" ? [] : ['<option value="none">none</option>'])
      .join("");
    tr.innerHTML = `<td class="num">${i + 1}</td><td>${f.key}</td>`
      + `<td><select data-key="${f.key}">${opts}</select></td><td class="tags"></td>`;
    const note = tr.lastElementChild;
    if (f.key === "mood") {
      note.innerHTML = 'happy = bpm &ge; <input type="number" id="cut" value="120" step="1" style="width:64px">';
      note.querySelector("#cut").addEventListener("input", e => {
        state.moodCut = Number(e.target.value) || 0;
        render();
      });
    } else {
      const tagged = SONGS.filter(s => (s[f.key] || []).length).length;
      note.textContent = `${tagged}/${SONGS.length} songs tagged`;
    }
    tr.querySelector("select").addEventListener("change", e => {
      state[e.target.dataset.key] = e.target.value;
      render();
    });
    controls.appendChild(tr);
  });
}

function render() {
  const act = active();
  const rows = SONGS.slice().sort((a, b) => {
    for (const f of act) {
      const d = scoreOf(b, f.key) - scoreOf(a, f.key);
      if (d) return d;
    }
    return a.title.localeCompare(b.title);
  });

  thead.innerHTML = '<tr><th class="num">#</th><th>title</th><th>artist</th>'
    + '<th class="num">bpm</th>'
    + act.map(f => `<th class="num">${f.key}</th>`).join("")
    + '<th>all tags</th></tr>';

  tbody.innerHTML = rows.map((s, i) => {
    const cells = act.map(f => {
      const v = scoreOf(s, f.key);
      const cls = v > 0 ? "pos" : v < 0 ? "neg" : "zero";
      const shown = f.key === "mood"
        ? (s.bpm == null ? "-" : s.bpm)
        : ((s[f.key] || []).join(",") || "-");
      return `<td class="num ${cls}">${shown} <span class="tags">${v > 0 ? "+1" : v < 0 ? "-1" : "0"}</span></td>`;
    }).join("");
    const all = FIELDS.filter(f => f.key !== "mood")
      .map(f => ({ key: f.key, v: (s[f.key] || []).join(",") }))
      .filter(x => x.v).map(x => `${x.key}:${x.v}`).join("  ");
    return `<tr><td class="num">${i + 1}</td><td>${esc(s.title)}</td>`
      + `<td>${esc(s.artist)}</td><td class="num">${s.bpm ?? "-"}</td>`
      + `${cells}<td class="tags">${all || "-"}</td></tr>`;
  }).join("");

  summary.textContent = act.length
    ? "ORDER BY " + act.map(f => f.key + " DESC").join(", ") + ", title"
    : "No category selected. Sorted by title.";
}

buildControls();
render();
</script>
</body>
</html>
"""


def load():
    con = sqlite3.connect(DB)
    con.row_factory = sqlite3.Row
    songs = []
    for r in con.execute("SELECT * FROM songs ORDER BY title"):
        song = dict(r)
        for f in FIELDS:
            song[f] = [v for v in (song.get(f) or "").split(",") if v]
        songs.append(song)
    return songs


def main():
    songs = load()
    OUT.write_text(TEMPLATE.replace("__SONGS__", json.dumps(songs, ensure_ascii=False)),
                   encoding="utf-8")
    print(f"{len(songs)} songs -> {OUT.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
