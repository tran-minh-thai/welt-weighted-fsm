#!/usr/bin/env python3
"""Runs of the voting analysis: every cell under the voter grid and the order modes, counters only.

    python3 tools/voting_probe.py run <out dir> [--cells NAME,...] [--modes grid,index,reverse,random]
    python3 tools/voting_probe.py regress <out dir> <campaign dir>
    python3 tools/voting_probe.py cells

run      compiles src/main/java into <out dir>/classes, then runs welt.runner.VotingProbeMain once per
         (cell, voter or mode) and appends its JSON line to <out dir>/runs.jsonl. A pair already in
         the file is skipped, so an interrupted run resumes. Refuses a source tree with changes.
         run_meta.json records the command, the commit, the source tree, the tool hashes and Java.
regress  compares every grid run of a cell of the voting experiment of the campaign with the row of
         that cell and voter in B2_voting_grid*.csv: N, M, R, backtracking nodes and embedding
         searches must be equal; and within every cell all runs must return the same result set.
         Prints the number of comparisons; exits 1 on a difference or when nothing was compared.
cells    prints the cells.

The counters are deterministic, so one run per pair is enough and any machine gives the same
values; the run time in the JSON line is a probe value, not a measurement.
"""
import csv
import hashlib
import json
import os
import platform
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
GRID = [f"{a / 5:.1f},{b / 5:.1f},{(5 - a - b) / 5:.1f}" for a in range(6) for b in range(6) if a + b <= 5] + ["1,1,1"]
SEEDS = [1, 2, 3, 4, 5]
MODES = {"index": ["index"], "reverse": ["reverse"], "random": [f"random:{s}" for s in SEEDS]}

# (name, dataset, tau_s, tau_w, group)
CELLS = [
    ("citeseer.200", "citeseer.lg", 200, "96.000", "e5"),
    ("citeseer.250", "citeseer.lg", 250, "96.000", "e5"),
    ("email_eu.10", "email_eu.lg", 10, "336.000", "e5"),
    ("email_eu.12", "email_eu.lg", 12, "336.000", "e5"),
    ("lastfm.600", "lastfm.lg", 600, "40.000", "e5"),
    ("mico.15000", "mico.lg", 15000, "100.000", "e5"),
    ("string_ecoli.1900", "string_ecoli.lg", 1900, "750.000", "e5"),
    ("citeseer.100", "citeseer.lg", 100, "96.000", "e5"),
    ("email_eu.6", "email_eu.lg", 6, "336.000", "e5"),
    ("lastfm.200", "lastfm.lg", 200, "40.000", "e5"),
    ("email_eu.4", "email_eu.lg", 4, "336.000", "larger"),
    ("email_eu.3", "email_eu.lg", 3, "336.000", "larger"),
    ("lastfm.100", "lastfm.lg", 100, "40.000", "larger"),
    ("citeseer.300.w96", "citeseer.lg", 300, "96.0", "high_tau_w"),
    ("citeseer.300.w90", "citeseer.lg", 300, "90.0", "high_tau_w"),
    ("email_eu.10.w4495", "email_eu.lg", 10, "4495.3", "high_tau_w"),
    ("email_eu.10.w3999", "email_eu.lg", 10, "3998.6", "high_tau_w"),
    ("email_eu.6.w4495", "email_eu.lg", 6, "4495.3", "high_tau_w"),
    ("email_eu.6.w3999", "email_eu.lg", 6, "3998.6", "high_tau_w"),
]


def sh(*cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def runs_of(out):
    f = out / "runs.jsonl"
    if not f.exists():
        return []
    return [json.loads(l) for l in f.read_text(encoding="utf-8").splitlines() if l.strip()]


def run(out, cells, modes):
    if sh("git", "-C", str(REPO), "status", "--porcelain", "--", "src", "tools").stdout.strip():
        sys.exit("voting_probe: the source tree has changes; commit them first")
    out.mkdir(parents=True, exist_ok=True)
    cls = out / "classes"
    if cls.exists():
        subprocess.run(["rm", "-rf", str(cls)], check=True)
    cls.mkdir()
    sources = [str(p) for p in sorted((REPO / "src/main/java").rglob("*.java"))]
    subprocess.run(["javac", "-nowarn", "-d", str(cls)] + sources, check=True, capture_output=True)
    home = str(Path.home())
    meta = {"command": " ".join(["python3", "tools/voting_probe.py"] + sys.argv[1:]).replace(home, "~"),
            "commit": sh("git", "-C", str(REPO), "rev-parse", "HEAD").stdout.strip(),
            "src_tree": sh("git", "-C", str(REPO), "rev-parse", "HEAD:src").stdout.strip(),
            "tool_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            "java": sh("java", "-version").stderr.splitlines()[0], "python": platform.python_version(),
            "machine": platform.node(), "grid": GRID, "seeds": SEEDS}
    (out / "run_meta.json").write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
    done = {(r["cell"], r["voter_or_mode"]) for r in runs_of(out)}
    env0 = {k: v for k, v in os.environ.items() if not k.startswith("WELT_")}
    with open(out / "runs.jsonl", "a", encoding="utf-8") as f:
        for name, ds, s, tw, group in CELLS:
            if name not in cells:
                continue
            todo = [("vote", v) for v in GRID] if "grid" in modes else []
            for m in modes:
                todo += [(x, "1,1,1") for x in MODES.get(m, [])]
            for mode, voter in todo:
                key = voter if mode == "vote" else mode
                if (name, key) in done:
                    continue
                env = dict(env0, WELT_VOTER=voter, WELT_ORDER_MODE=mode, WELT_VOTER_GRID=";".join(GRID))
                p = sh("java", "-Xmx8g", "-cp", str(cls), "welt.runner.VotingProbeMain", str(REPO / "datasets" / ds),
                       str(s), tw, env=env)
                line = [l for l in p.stdout.splitlines() if l.startswith("JSON,")]
                if p.returncode != 0 or len(line) != 1:
                    sys.exit(f"voting_probe: {name} {key} failed (exit {p.returncode})\n{p.stderr[-2000:]}")
                rec = json.loads(line[0][5:])
                rec.update(cell=name, group=group, voter_or_mode=key)
                f.write(json.dumps(rec) + "\n")
                f.flush()
                print(f"{name:<20} {key:<12} N {rec['N']:>8} M {rec['M']:>7} backtrack {rec['backtrack']:>9} "
                      f"{rec['totalMs']:.0f} ms", flush=True)


def regress(out, campaign):
    rows = {}
    for fn in ("B2_voting_grid.csv", "B2_voting_grid_hard.csv", "B1_efficiency.csv", "B1_low.csv", "B5_tau_sweep.csv",
               "B5_welt_only.csv"):
        for r in csv.DictReader(open(campaign / fn, encoding="utf-8")):
            if r.get("algorithm", "WeLT") == "WeLT" and r.get("status") == "OK":
                rows.setdefault((r["dataset"], int(r["minSup"]), float(r["minWeight"]), r["voter"]), r)
    n = bad = 0
    sets = {}
    for rec in runs_of(out):
        sets.setdefault(rec["cell"], set()).add(rec["result_sha256"])
        r = None if rec["mode"] != "vote" else rows.get((rec["dataset"], rec["minSup"], float(rec["minWeight"]), rec["voter"]))
        for ours, theirs in [] if r is None else (("N", "N"), ("M", "M"), ("R", "R"), ("backtrack", "backtrack"),
                             ("mniIsoCallsMine", "mniIsoCallsMine"), ("weightIsoCalls", "weightIsoCalls")):
            n += 1
            if int(rec[ours]) != int(r[theirs]):
                bad += 1
                print(f"DIFF {rec['cell']} voter {rec['voter']} {ours}: here {rec[ours]}, campaign {r[theirs]}")
        s = rec["stats"]
        parts = [(sum(s[f"{ph}.{k}.backtrack"] for ph in ("table", "mining") for k in ("mni", "weight")), rec["backtrack"]),
                 (s["table.mni.searches"] + s["mining.mni.searches"], rec["mniIsoCalls"]),
                 (s["table.weight.searches"] + s["mining.weight.searches"], rec["weightIsoCalls"]),
                 (s["mining.mni.searches"], rec["mniIsoCallsMine"]),
                 (s["mining.weight.pass_calls"] + s["mining.weight.fail_calls"], s["mining.weight.calls"]),
                 (s["mining.mni.searched_values"], s["mining.mni.searches"])]
        for got, want in parts:
            n += 1
            if got != want:
                bad += 1
                print(f"DIFF {rec['cell']} {rec['voter_or_mode']}: a part sums to {got}, the total is {want}")
    for cell, hs in sets.items():
        n += 1
        if len(hs) != 1:
            bad += 1
            print(f"DIFF {cell}: {len(hs)} different result sets across voters and modes")
    print(f"voting_probe regress: {n} comparisons, {bad} differences")
    sys.exit(1 if bad or n == 0 else 0)


def main():
    a = sys.argv[1:]
    if a[0] == "cells":
        for c in CELLS:
            print(*c)
        return
    out = Path(a[1])
    if a[0] == "run":
        cells = a[a.index("--cells") + 1].split(",") if "--cells" in a else [c[0] for c in CELLS]
        modes = a[a.index("--modes") + 1].split(",") if "--modes" in a else ["grid", "index", "reverse", "random"]
        unknown = set(cells) - {c[0] for c in CELLS}
        if unknown:
            sys.exit(f"voting_probe: unknown cells {sorted(unknown)}")
        run(out, cells, modes)
    elif a[0] == "regress":
        regress(out, Path(a[2]))


if __name__ == "__main__":
    main()
