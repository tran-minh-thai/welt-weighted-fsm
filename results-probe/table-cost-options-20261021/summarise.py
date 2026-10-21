#!/usr/bin/env python3
"""Counts of the table cost-option probe (standard library only).

Reads raw.csv (one BenchmarkMain row per cell and combination) and patterns/<reuse>_<threshold>/,
writes counts.csv (count columns only; run times of this probe are not measurements) and prints,
per cell, the checks: same result set and same search counters in the four combinations, and the
embedding searches removed by each option.
"""
import csv
import os

HERE = os.path.dirname(os.path.abspath(__file__))
KEEP = ["dataset", "minSup", "minWeight", "tableReuse", "tableThreshold", "status", "N", "M", "Mprime", "R", "extended",
        "prunedP1", "prunedP2", "prunedP2Single", "prunedP3", "tableSize", "tableBuildCandidates", "tableBuildMniIsoCalls",
        "tableBuildWeightIsoCalls", "mniIsoCallsMine", "mniIsoCallsTotal", "weightIsoCalls", "supportReused",
        "weightReused", "backtrack", "backtrackMni", "backtrackWeight"]
SEARCH = ["N", "M", "Mprime", "R", "extended", "prunedP1", "prunedP2", "prunedP2Single", "prunedP3", "tableSize"]

rows = list(csv.DictReader(open(os.path.join(HERE, "raw.csv"), encoding="utf-8")))
with open(os.path.join(HERE, "counts.csv"), "w", newline="", encoding="utf-8") as fh:
    w = csv.writer(fh, lineterminator="\n")
    w.writerow(KEEP)
    for r in rows:
        w.writerow([r[k] for k in KEEP])


def codes(r):
    ds = r["dataset"][:-3]
    f = os.path.join(HERE, "patterns", f'{r["tableReuse"]}_{r["tableThreshold"]}',
                     f'{ds}_WeLT_{r["minSup"]}_{float(r["minWeight"])}.tsv')
    return set(l.split("\t")[0] for l in open(f, encoding="utf-8").read().splitlines()[1:]) if os.path.exists(f) else None


cells = {}
for r in rows:
    cells.setdefault((r["dataset"], int(r["minSup"])), {})[(r["tableReuse"], r["tableThreshold"])] = r
checked = same = 0
tot = {"mni": [0, 0], "weight": [0, 0], "all": [0, 0], "bt": [0, 0]}
print(f'{"cell":20}{"status":>7}{"R":>5}  {"same":>5}  {"MNI searches off -> reuse":>27}  {"weight searches off -> threshold":>33}  {"all searches off -> both":>26}')
for k in sorted(cells):
    c = cells[k]
    base = c[("off", "off")]
    if base["status"] != "OK" or any(c[x]["status"] != "OK" for x in c):
        print(f'{k[0]:13}{k[1]:>7}  not completed in every combination: {sorted((x, c[x]["status"]) for x in c)}')
        continue
    checked += 1
    ok = all(codes(c[x]) == codes(base) and [c[x][s] for s in SEARCH] == [base[s] for s in SEARCH] for x in c)
    same += ok
    mni = (int(base["mniIsoCallsTotal"]), int(c[("on", "off")]["mniIsoCallsTotal"]))
    wt = (int(base["weightIsoCalls"]), int(c[("off", "on")]["weightIsoCalls"]))
    a0 = int(base["mniIsoCallsTotal"]) + int(base["weightIsoCalls"])
    a1 = int(c[("on", "on")]["mniIsoCallsTotal"]) + int(c[("on", "on")]["weightIsoCalls"])
    for key, (x, y) in (("mni", mni), ("weight", wt), ("all", (a0, a1)),
                        ("bt", (int(base["backtrack"]), int(c[("on", "on")]["backtrack"])))):
        tot[key][0] += x
        tot[key][1] += y
    print(f'{k[0]:13}{k[1]:>7}{base["status"]:>7}{base["R"]:>5}  {"yes" if ok else "NO":>5}  {mni[0]:>12} -> {mni[1]:<12}  {wt[0]:>15} -> {wt[1]:<15}  {a0:>11} -> {a1:<11}')
print(f"\ncells compared {checked}; identical result set and search counters in all four combinations: {same}/{checked}")
for key, label in (("mni", "MNI searches (reuse)"), ("weight", "weight searches (threshold)"), ("all", "all searches (both)"),
                   ("bt", "backtrack nodes (both)")):
    x, y = tot[key]
    print(f"  {label:28} {x} -> {y}  ({100 * (x - y) / x:.1f}% removed)" if x else f"  {label}: none")
