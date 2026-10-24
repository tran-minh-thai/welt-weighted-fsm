#!/usr/bin/env python3
"""Summary tables of a revision campaign directory (standard library only).

Usage:  python tools/summarise_campaign.py <campaign dir> [out.md]

Reads the CSV files written by revision.sh and prints, as Markdown:
  1. efficiency: WeLT against GraMiFilter (same result set), GraMi and WEGM on the b1 and b1low cells;
  2. the gating invariant: WeLT extends exactly its result patterns (extended = R), GraMi its frequent ones;
  3. ablation at the b4, b4hard and b4low cells, per variant against full;
  4. voting-weight sensitivity (backtracking nodes over the grid);
  5. peak live heap and retained data;
  6. the tau_w sweep and the WeLT-only sweep;
  7. WEGM, the exact ALL score and WeLT at WEGM's supports, and the nesting check;
  8. the ablation repeated in three JVM passes (b4rep), read by the rule of rep_verdict.
Tie rule: |difference| < 3 * sqrt(sd_a^2 + sd_b^2) over the measured runs of each side; the sd is within
one JVM, so it does not include the variation between JVMs (section 4 measures that variation where the
search counters are identical).
"""
import collections
import csv
import math
import os
import statistics
import sys

D = sys.argv[1] if len(sys.argv) > 1 else None
OUT = []


def p(line=""):
    OUT.append(line)


def load(name):
    path = os.path.join(D, name)
    if not os.path.exists(path):
        p(f"*{name}: missing*")
        return []
    with open(path, newline="", encoding="utf-8") as fh:
        return list(csv.DictReader(fh))


def ms(r):
    return float(r["totalMs"])


def verdict(a, b):
    """Tie, 'a faster' or 'b faster' by the tie rule."""
    s = math.hypot(float(a["totalSdMs"]), float(b["totalSdMs"]))
    d = ms(a) - ms(b)
    return "tie" if abs(d) < 3 * s else ("faster" if d < 0 else "slower")


def ok(r):
    return r is not None and r["status"] == "OK"


def efficiency():
    p("## 1. Efficiency (b1, b1low)")
    d = collections.defaultdict(dict)
    for r in load("B1_efficiency.csv") + load("B1_low.csv"):
        d[(r["dataset"][:-3], int(r["minSup"]))][r["algorithm"]] = r
    p("| cell | WeLT ms | R | N | GraMiFilter ms | N | GraMi ms | WEGM ms | WEGM R | WeLT vs GraMiFilter |")
    p("|---|---|---|---|---|---|---|---|---|---|")
    count, ratio = collections.Counter(), []
    for k in sorted(d, key=lambda k: (k[0], -k[1])):
        w, f, g, e = (d[k].get(a) for a in ("WeLT", "GraMiFilter", "GraMi", "WEGM"))
        cell = lambda r: (f"{ms(r):.1f}" if ok(r) else (r["status"] if r else "-"))
        if ok(w) and ok(f):
            v = {"faster": "WeLT faster", "slower": "WeLT slower", "tie": "tie"}[verdict(w, f)]
            ratio.append(ms(f) / ms(w))
        elif ok(w) and f is not None:
            v = "only WeLT completes"
        elif f is None:
            v = "GraMiFilter not run"
        else:
            v = "neither completes"
        count[v] += 1
        p(f"| {k[0]} {k[1]} | {cell(w)} | {(w or {}).get('R', '')} | {(w or {}).get('N', '')} | {cell(f)} | "
          f"{(f or {}).get('N', '')} | {cell(g)} | {cell(e)} | {(e or {}).get('R', '')} | {v} |")
    p()
    p(f"Cells: {sum(count.values())}; " + ", ".join(f"{k}: {v}" for k, v in sorted(count.items())))
    if ratio:
        p(f"GraMiFilter / WeLT time where both complete (n = {len(ratio)}): min {min(ratio):.2f}, "
          f"median {statistics.median(ratio):.2f}, max {max(ratio):.1f}")
    wegm = [r for r in load("B1_efficiency.csv") if r["algorithm"] == "WEGM" and ok(r)]
    p(f"WEGM cells with at least one pattern: {sum(int(r['R']) > 0 for r in wegm)} of {len(wegm)}")
    p()
    return d


def gating(d):
    p("## 2. Gating invariant: patterns extended")
    p("| cell | WeLT N | WeLT R | WeLT extended | GraMi N | GraMi frequent | GraMi extended | N ratio | frequent / R |")
    p("|---|---|---|---|---|---|---|---|---|")
    checked = held = 0
    for k in sorted(d, key=lambda k: (k[0], -k[1])):
        w, g = d[k].get("WeLT"), d[k].get("GraMi")
        if not ok(w):
            continue
        checked += 1
        held += int(w["extended"]) == int(w["R"])
        tail = (f"{g['N']} | {g['R']} | {g['extended']} | {int(g['N']) / max(1, int(w['N'])):.1f} | "
                f"{int(g['R']) / max(1, int(w['R'])):.1f}") if ok(g) else "| | | |"
        p(f"| {k[0]} {k[1]} | {w['N']} | {w['R']} | {w['extended']} | {tail} |")
    p()
    p(f"WeLT extended = R in {held} of {checked} completed cells")
    p()


def ablation():
    variants = ["noP1", "noP2", "noP2single", "noTable", "noTableBuild", "noP3", "p3R", "noWext", "noBnb", "voteDeg",
                "noWorder", "noTableReuse", "noTableThreshold", "noTableOptions"]
    for name in ("B4_ablation.csv", "B4_ablation_hard.csv", "B4_low.csv"):
        rows = load(name)
        if not rows:
            continue
        cells = collections.defaultdict(dict)
        for r in rows:
            cells[(r["dataset"][:-3], int(r["minSup"]))][r["variant"]] = r
        bad = [(k, v) for k, c in cells.items() for v, r in c.items() if ok(r) and r.get("invariant") not in ("OK", "REF")]
        p(f"## 3. Ablation, {name} ({len(cells)} cells, result-set violations: {len(bad)})")
        p("| variant | " + " | ".join(f"{k[0]} {k[1]}" for k in sorted(cells, key=lambda k: (k[0], -k[1]))) + " | summary |")
        p("|---|" + "---|" * (len(cells) + 1))
        for v in variants:
            out, row = collections.Counter(), []
            for k in sorted(cells, key=lambda k: (k[0], -k[1])):
                f, r = cells[k].get("full"), cells[k].get(v)
                if r is None or not ok(f):
                    row.append("-")
                    continue
                if not ok(r):
                    out[r["status"]] += 1
                    row.append(r["status"])
                    continue
                t = verdict(r, f)
                out[t] += 1
                row.append(f"{ms(r) / ms(f):.2f}" + {"faster": " −", "slower": " +", "tie": ""}[t])
            if out:
                p(f"| {v} | " + " | ".join(row) + " | " + ", ".join(f"{a} {b}" for a, b in sorted(out.items())) + " |")
        p()
    p("Cells give variant time / full time; + slower, − faster by the tie rule.")
    p()


def voting():
    p("## 4. Voting weights: backtracking nodes over the grid")
    p("| cell | voters completed | backtrack at (1,1,1) | min | max | max / min | time max / min |")
    p("|---|---|---|---|---|---|---|")
    for name in ("B2_voting_grid.csv", "B2_voting_grid_hard.csv"):
        c = collections.defaultdict(list)
        for r in load(name):
            c[(r["dataset"][:-3], int(r["minSup"]))].append(r)
        for k in sorted(c):
            done = [r for r in c[k] if ok(r)]
            bt = [int(r["backtrack"]) for r in done]
            t = [ms(r) for r in done]
            base = [int(r["backtrack"]) for r in done if r["voter"].replace(";", ",") in ("1,1,1", "1.0,1.0,1.0")]
            p(f"| {k[0]} {k[1]} | {len(done)}/{len(c[k])} | {base[0] if base else '-'} | {min(bt)} | {max(bt)} | "
              f"{max(bt) / max(1, min(bt)):.2f} | {max(t) / min(t):.2f} |")
    p()


def memory():
    p("## 5. Peak live heap (untimed runs) and retained data")
    p("| cell | algorithm | N | graph MB | live peak MB | visited code chars | memo chars | frontier ids | table ids |")
    p("|---|---|---|---|---|---|---|---|---|")
    for r in load("B3_live_heap.csv"):
        p(f"| {r['dataset'][:-3]} {r['minSup']} | {r['algorithm']} | {r['N']} | {float(r['liveHeapGraphMB']):.1f} | "
          f"{float(r['liveHeapPeakMB'] or 'nan'):.1f} | {r['visitedCodeChars']} | {r['memoCodeChars']} | "
          f"{r['frontierDomainIntsPeak']} | {r['tableDomainInts']} |")
    p()


def sweep():
    p("## 6. tau_w sweep")
    allx = {}
    for r in load("B5_all_exact.csv"):
        allx[(r["dataset"].split("_heavy_")[0].replace(".lg", ""), int(r["minSup"]), round(float(r["minWeight"]), 4))] = r
    c = collections.defaultdict(dict)
    for r in load("B5_tau_sweep.csv"):
        c[(r["dataset"][:-3], int(r["minSup"]), round(float(r["minWeight"]), 4))][r["algorithm"]] = r
    cell = lambda r: (f"{ms(r):.1f} / {r['R']}" if ok(r) else (r["status"] if r else "-"))
    p("| cell | GraMiFilter ms / R | WEGM ms / R | WeLT ms / R | exact ALL ms / R | WeLT vs GraMiFilter |")
    p("|---|---|---|---|---|---|")
    for k in sorted(c):
        g, e, w = (c[k].get(a) for a in ("GraMiFilter", "WEGM", "WeLT"))
        v = f"{ms(g) / ms(w):.2f}x {verdict(w, g)}" if ok(g) and ok(w) else ""
        p(f"| {k[0]} {k[1]} {k[2]} | {cell(g)} | {cell(e)} | {cell(w)} | {cell(allx.get(k))} | {v} |")
    p()
    p("| WeLT-only cell | status | ms | R |")
    p("|---|---|---|---|")
    for r in load("B5_welt_only.csv"):
        p(f"| {r['dataset'][:-3]} {r['minSup']} {float(r['minWeight']):.4f} | {r['status']} | {ms(r):.1f} | {r['R']} |")
    p()


def wegm_range():
    p("## 7. WEGM's supports: WEGM, exact ALL, WeLT (one run each)")
    c = collections.defaultdict(dict)
    for r in load("B6_wegm_range.csv"):
        c[(r["dataset"][:-3], int(r["minSup"]))][r["algorithm"]] = r
    for r in load("B6_wegm_range_all.csv"):
        c[(r["dataset"].split("_heavy_")[0], int(r["minSup"]))]["ALL"] = r
    cell = lambda r: (f"{r['R']} in {ms(r):.1f} ms" if ok(r) else (r["status"] if r else "-"))
    p("| cell | WEGM | exact ALL | WeLT |")
    p("|---|---|---|---|")
    for k in sorted(c):
        p(f"| {k[0]} {k[1]} | {cell(c[k].get('WEGM'))} | {cell(c[k].get('ALL'))} | {cell(c[k].get('WeLT'))} |")
    p()
    rows = load("B7_nesting.csv")
    compared = [r for r in rows if r["exit"].strip() == "0"]
    bad = [r for r in rows if r["exit"].strip() == "1"]
    p(f"Nesting (skeletons of WEGM within ALL within WeLT): {len(compared)} cells compared, {len(bad)} violations, "
      f"{len(rows) - len(compared) - len(bad)} not compared")
    p()


def rep_verdict(full, var):
    """Verdict of a variant over the JVM passes.

    full, var: per-pass median times (same passes, same order). Slower (faster) only if the variant is
    slower (faster) in every pass and its smallest (largest) ratio to full lies outside the spread of
    full over the passes (max/min of the full times); otherwise tie.
    """
    ratios = [v / f for f, v in zip(full, var)]
    spread = max(full) / min(full)
    if all(r > 1 for r in ratios) and min(ratios) > spread:
        return "slower", ratios, spread
    if all(r < 1 for r in ratios) and max(ratios) < 1 / spread:
        return "faster", ratios, spread
    return "tie", ratios, spread


def repeated():
    passes = [load(f"B4_rep_jvm{i}.csv") for i in (1, 2, 3)]
    if not all(passes):
        return
    p("## 8. Ablation repeated in three JVM passes")
    by = [collections.defaultdict(dict) for _ in passes]
    for b, rows in zip(by, passes):
        for r in rows:
            b[(r["dataset"][:-3], int(r["minSup"]))][r["variant"]] = r
    p("| cell | variant | ms per pass | ratio to full per pass | full spread | verdict |")
    p("|---|---|---|---|---|---|")
    for k in sorted(by[0], key=lambda k: (k[0], -k[1])):
        if not all(ok(b[k].get("full")) for b in by if k in b):
            p(f"| {k[0]} {k[1]} | full | not complete in every pass | | | |")
            continue
        full = [ms(b[k]["full"]) for b in by]
        for v in by[0][k]:
            rs = [b[k].get(v) for b in by]
            if v == "full" or not all(ok(r) for r in rs):
                continue
            t, ratios, spread = rep_verdict(full, [ms(r) for r in rs])
            p(f"| {k[0]} {k[1]} | {v} | {' / '.join(f'{ms(r):.0f}' for r in rs)} | "
              f"{' / '.join(f'{x:.2f}' for x in ratios)} | {spread:.2f} | {t} |")
    p()


def main():
    if D is None or not os.path.isdir(D):
        sys.exit("usage: summarise_campaign.py <campaign dir> [out.md]")
    p(f"# Campaign summary: {os.path.basename(os.path.normpath(D))}")
    p()
    d = efficiency()
    gating(d)
    ablation()
    voting()
    memory()
    sweep()
    wegm_range()
    repeated()
    text = "\n".join(OUT) + "\n"
    if len(sys.argv) > 2:
        with open(sys.argv[2], "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
    print(text)


if __name__ == "__main__":
    main()
