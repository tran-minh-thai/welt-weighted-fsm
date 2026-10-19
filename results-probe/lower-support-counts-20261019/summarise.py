#!/usr/bin/env python3
"""Count-only summary of the low-support probe (standard library only).

Usage:  python summarise.py        (run inside this directory)

Reads raw_<dataset>.csv and writes counts.csv with count columns only: run times of this
probe are not measurements and are left out. `complete` is 1 for a cell that finished
(its counts hold on every machine) and 0 for a cell stopped by the 10-minute limit (its
counts are the values reached at the stop and depend on the machine).
"""
import csv
import glob

KEEP = ["dataset", "minSup", "minWeight", "algorithm", "status", "N", "M", "Mtotal", "Mprime", "R",
        "prunedStrategy", "prunedP1", "prunedP2", "prunedP2Single", "prunedEdgeType", "prunedP3",
        "extended", "mniIsoCallsMine", "mniIsoCallsTotal", "weightIsoCalls", "backtrack"]

rows = []
for path in sorted(glob.glob("raw_*.csv")):
    with open(path, newline="", encoding="utf-8") as fh:
        rows += list(csv.DictReader(fh))
with open("counts.csv", "w", newline="", encoding="utf-8") as fh:
    w = csv.writer(fh)
    w.writerow(KEEP + ["complete"])
    for r in rows:
        w.writerow([r.get(k, "") for k in KEEP] + [1 if r["status"] == "OK" else 0])
print(f"counts.csv: {len(rows)} cells, {sum(r['status'] == 'OK' for r in rows)} complete")
