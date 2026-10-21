#!/usr/bin/env python3
"""Search counts of WeLT, with the table cost options off and on, against EdgeTypeBound (standard library only).

WeLT counts come from counts.csv of this probe; EdgeTypeBound counts and the campaign tie verdict come from the B1
file of the measurement campaign, read with git from the campaign branch. Counts are deterministic, so the two
sources compare; the run with both options off must reproduce the campaign counts of WeLT in every cell, and the
script stops if it does not. Writes compare_edgetypebound.txt.

Usage (from anywhere): python compare_edgetypebound.py [git ref of the campaign branch]
"""
import csv
import io
import math
import os
import statistics
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
REF = sys.argv[1] if len(sys.argv) > 1 else "origin/campaign-revision-r1c"
B1 = "results/v2_revision/20261019_2137_Admin-PC/B1_efficiency.csv"


def key(r):
    return r["dataset"], int(r["minSup"]), float(r["minWeight"])


def work(r):
    """(embedding searches, backtracking nodes)."""
    return int(r["mniIsoCallsTotal"]) + int(r["weightIsoCalls"]), int(r["backtrack"])


text = subprocess.run(["git", "-C", REPO, "show", f"{REF}:{B1}"], capture_output=True, text=True, check=True).stdout
camp = {(key(r), r["algorithm"]): r for r in csv.DictReader(io.StringIO(text))}
probe = {(key(r), r["tableReuse"], r["tableThreshold"]): r
         for r in csv.DictReader(open(os.path.join(HERE, "counts.csv"), encoding="utf-8"))}

out = [f"campaign file {REF}:{B1}"]
rows, reproduced = [], 0
for k in sorted({k for (k, _, _) in probe}):
    e, w = camp.get((k, "EdgeTypeBound")), camp.get((k, "WeLT"))
    off, on = probe.get((k, "off", "off")), probe.get((k, "on", "on"))
    if not (e and w and off and on) or e["status"] != "OK" or w["status"] != "OK":
        out.append(f"not comparable {k}: EdgeTypeBound {e and e['status']}, WeLT {w and w['status']}")
        continue
    if work(off) != work(w):
        sys.exit(f"{k}: probe counts {work(off)} differ from campaign counts {work(w)}")
    reproduced += 1
    se, be = work(e)
    if se == 0 or be == 0:
        out.append(f"no search in either method {k}: EdgeTypeBound {work(e)}, WeLT on {work(on)}")
        continue
    s0, b0 = work(off)
    s1, b1 = work(on)
    te, tw = float(e["totalMs"]), float(w["totalMs"])
    tie = abs(tw - te) < 3 * math.sqrt(float(e["totalSdMs"]) ** 2 + float(w["totalSdMs"]) ** 2)
    rows.append((k, s0 / se, s1 / se, b0 / be, b1 / be, s1 - se, tw / te, tie))

out.append(f"WeLT counts with both options off reproduce the campaign in {reproduced} cells")
out.append(f"{'cell':20} {'searches off/ETB':>16} {'on/ETB':>7} {'backtrack off/ETB':>17} {'on/ETB':>7} "
           f"{'searches on-ETB':>15} {'campaign time WeLT/ETB':>22}")
for k, a, b, c, d, extra, t, tie in rows:
    out.append(f"{k[0][:-3] + ' ' + str(k[1]):20} {a:16.3f} {b:7.3f} {c:17.3f} {d:7.3f} {extra:15d} "
               f"{t:18.2f} {'tie' if tie else 'diff'}")


def geo(xs):
    return math.exp(statistics.fmean(math.log(x) for x in xs))


n = len(rows)
out.append(f"compared cells {n}; geometric mean searches WeLT/EdgeTypeBound off {geo([r[1] for r in rows]):.3f}, "
           f"on {geo([r[2] for r in rows]):.3f}; backtracking off {geo([r[3] for r in rows]):.3f}, "
           f"on {geo([r[4] for r in rows]):.3f}")
for name, i in (("searches", 2), ("backtracking nodes", 4)):
    out.append(f"options on, {name}: WeLT fewer in {sum(r[i] < 1 - 1e-9 for r in rows)}, equal in "
               f"{sum(abs(r[i] - 1) <= 1e-9 for r in rows)}, more in {sum(r[i] > 1 + 1e-9 for r in rows)} of {n}")
out.append(f"campaign time verdict (options off): tie in {sum(r[7] for r in rows)} of {n}")
open(os.path.join(HERE, "compare_edgetypebound.txt"), "w", encoding="utf-8", newline="\n").write("\n".join(out) + "\n")
print("\n".join(out))
