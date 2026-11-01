#!/usr/bin/env python3
"""Spread of the measured run times: delta/t over the timed cells of Experiments 1 and 3.

t is the median and delta the sample standard deviation (n - 1) of the measured runs of one cell, as
written by BenchmarkMain (totalMs, totalSdMs) and exported as <cell>.ms and <cell>.sd_ms. A cell is one
algorithm at one configuration that completes (status OK); a cell that reaches the limit runs once
and has no spread.

  Experiment 1  every e1.cell.<ds>.<tau_s>.<algorithm> with status OK: GraMi, GraMiFilter, WEGM, WeLT
                (campaign r2 numbers) and WeLT-noTableBuild (round-6 numbers)
  higher tau_w  every r5s.<ds>.<tau_s>.<tau_w>.<algorithm> with status OK (round-5 and round-6 numbers)
  Experiment 3  the GraMiFilter and WeLT rows of B5_tau_sweep.csv, the two series that the tie rule
                compares and Figure 3 draws; each row is matched to its point e3.<ds>.p<i> by tau_w and
                its median must equal the exported one. The shared-table sweep (B5_shared_table.csv)
                records medians only, so it has no spread to report.

Keys (prefix e1, r5s, e3): <p>.sd_rel.{cells,max,median}, <p>.sd_rel.ge1s.{cells,max,max.cell} over the cells whose
median is at least LONG_MS (1,000 ms; absent when a group has none), <p>.sd_rel.max.cell ("<ds> <tau_s> <algorithm>",
"<ds> <tau_s> <tau_w> <algorithm>" for r5s, "<ds> <tau_w> <algorithm>" for e3; cells with equal maxima
joined by "; "), and e3.<ds>.p<i>.<algorithm>.sd_ms for the Experiment 3 rows.

Usage:
  export_time_spread.py <campaign r2 numbers.json> <round-6 numbers.json> <round-5 numbers.json> \\
      <campaign r2 directory> --out <numbers.json>
  export_time_spread.py --self-test
"""
import argparse
import csv
import json
import os
import statistics
import sys

E3_ALGORITHMS = ("GraMiFilter", "WeLT")
LONG_MS = 1000.0


def values(path):
    with open(path, encoding="utf-8") as f:
        d = json.load(f)
    return d.get("values", d)


def cells(v, prefix, depth):
    """{cell: (ms, sd_ms)} of the keys <prefix>.<depth parts>.<algorithm>.status == OK."""
    out, missing = {}, []
    for k, s in v.items():
        if not (k.startswith(prefix + ".") and k.endswith(".status")) or s != "OK":
            continue
        b = k[:-len(".status")]
        if len(b.split(".")) != len(prefix.split(".")) + depth:
            continue
        if b + ".ms" not in v or b + ".sd_ms" not in v:
            missing.append(b)
            continue
        out[b] = (float(v[b + ".ms"]), float(v[b + ".sd_ms"]))
    if missing:
        sys.exit(f"{prefix}: completed cells without ms or sd_ms: {', '.join(sorted(missing)[:5])}")
    return out


def summary(prefix, spread, name):
    """The sd_rel keys of one group; spread: {cell: (ms, sd_ms)}, name: cell -> printed cell."""
    if not spread:
        sys.exit(f"{prefix}: no completed cell")
    rel = {c: sd / ms for c, (ms, sd) in spread.items()}
    top = max(rel.values())
    out = {f"{prefix}.sd_rel.cells": len(rel), f"{prefix}.sd_rel.max": top,
           f"{prefix}.sd_rel.median": statistics.median(rel.values()),
           f"{prefix}.sd_rel.max.cell": "; ".join(sorted(name(c) for c, r in rel.items() if r == top))}
    long = {c: r for c, r in rel.items() if spread[c][0] >= LONG_MS}
    if long:
        top = max(long.values())
        out.update({f"{prefix}.sd_rel.ge1s.cells": len(long), f"{prefix}.sd_rel.ge1s.max": top,
                    f"{prefix}.sd_rel.ge1s.max.cell": "; ".join(sorted(name(c) for c, r in long.items() if r == top))})
    return out


def e3_rows(v, rows):
    """{e3 point cell: (ms, sd_ms)} of the B5_tau_sweep.csv rows of E3_ALGORITHMS, checked against v."""
    out = {}
    for r in rows:
        if r["algorithm"] not in E3_ALGORITHMS:
            continue
        d, tw = r["dataset"].rsplit(".", 1)[0], float(r["minWeight"])
        n = v.get(f"e3.{d}.points")
        if n is None:
            sys.exit(f"B5_tau_sweep.csv: {d} has no exported points")
        pts = [i for i in range(n) if abs(v[f"e3.{d}.p{i}.tau_w"] - tw) < 5e-4]
        if len(pts) != 1:
            sys.exit(f"B5_tau_sweep.csv: {d} tau_w {tw}: {len(pts)} exported points match")
        b = f"e3.{d}.p{pts[0]}.{r['algorithm']}"
        if r["status"] != "OK" or v.get(b + ".status") != "OK":
            sys.exit(f"{b}: status {r['status']} in the CSV, {v.get(b + '.status')} exported")
        if float(r["totalMs"]) != v[b + ".ms"]:
            sys.exit(f"{b}: median {r['totalMs']} in the CSV, {v[b + '.ms']} exported")
        if b in out:
            sys.exit(f"{b}: two rows")
        out[b] = (float(r["totalMs"]), float(r["totalSdMs"]))
    want = {f"e3.{d}.p{i}.{a}" for d in {k.split(".")[1] for k in v if k.startswith("e3.") and k.endswith(".points")}
            for i in range(v[f"e3.{d}.points"]) for a in E3_ALGORITHMS}
    if set(out) != want:
        sys.exit(f"B5_tau_sweep.csv: points without a row: {sorted(want - set(out))[:5]}")
    return out


def export(campaign, round6, round5, sweep_rows):
    clash = (set(campaign) & set(round6)) | (set(campaign) & set(round5)) | (set(round6) & set(round5))
    if clash:
        sys.exit("keys present in two data files: " + ", ".join(sorted(clash)[:5]))
    v = {**campaign, **round6, **round5}
    out = {}
    e1 = cells(v, "e1.cell", 3)
    out.update(summary("e1", e1, lambda c: " ".join(c.split(".")[2:])))
    r5 = cells(v, "r5s", 4)
    out.update(summary("r5s", r5, lambda c: " ".join(c.split(".")[1:])))
    e3 = e3_rows(v, sweep_rows)
    out.update(summary("e3", e3, lambda c: f"{c.split('.')[1]} {v[c.rsplit('.', 1)[0] + '.tau_w']:g} {c.split('.')[-1]}"))
    for c, (_ms, sd) in e3.items():
        out[c + ".sd_ms"] = sd
    return out, {"e1": len(e1), "r5s": len(r5), "e3": len(e3)}


def self_test():
    fails = 0

    def check(name, got, want):
        nonlocal fails
        print(("ok   " if got == want else "FAIL ") + f"{name}: got {got}, want {want}")
        fails += got != want

    def refused(name, f):
        nonlocal fails
        try:
            f()
        except SystemExit as e:
            print(f"ok   {name}: refused ({e})")
            return
        print(f"FAIL {name}: not refused")
        fails += 1

    camp = {"e1.cell.a.10.WeLT.status": "OK", "e1.cell.a.10.WeLT.ms": 100.0, "e1.cell.a.10.WeLT.sd_ms": 5.0,
            "e1.cell.a.6.WeLT.status": "OK", "e1.cell.a.6.WeLT.ms": 1000.0, "e1.cell.a.6.WeLT.sd_ms": 30.0,
            "e1.cell.a.6.GraMi.status": "OK", "e1.cell.a.6.GraMi.ms": 999.9, "e1.cell.a.6.GraMi.sd_ms": 90.0,
            "e1.cell.a.6.WEGM.status": "OK", "e1.cell.a.6.WEGM.ms": 4000.0, "e1.cell.a.6.WEGM.sd_ms": 40.0,
            "e1.cell.a.10.GraMi.status": "OK", "e1.cell.a.10.GraMi.ms": 10.0, "e1.cell.a.10.GraMi.sd_ms": 4.0,
            "e1.cell.a.8.GraMi.status": "TO", "e1.cell.a.8.GraMi.ms": 3600000.0,      # over the limit: no spread
            "e3.a.points": 2, "e3.a.p0.tau_w": 1.5, "e3.a.p1.tau_w": 2.0,
            "e3.a.p0.WeLT.status": "OK", "e3.a.p0.WeLT.ms": 20.0, "e3.a.p1.WeLT.status": "OK", "e3.a.p1.WeLT.ms": 30.0,
            "e3.a.p0.GraMiFilter.status": "OK", "e3.a.p0.GraMiFilter.ms": 40.0,
            "e3.a.p1.GraMiFilter.status": "OK", "e3.a.p1.GraMiFilter.ms": 50.0}
    r6 = {"e1.cell.a.10.noTableBuild.status": "OK", "e1.cell.a.10.noTableBuild.ms": 50.0,
          "e1.cell.a.10.noTableBuild.sd_ms": 1.0,
          "r5s.a.10.w5.WeLT.status": "OK", "r5s.a.10.w5.WeLT.ms": 8.0, "r5s.a.10.w5.WeLT.sd_ms": 2.0}
    r5 = {"r5s.a.10.w5.WEGM.status": "OK", "r5s.a.10.w5.WEGM.ms": 4.0, "r5s.a.10.w5.WEGM.sd_ms": 1.0}
    row = lambda a, tw, ms, sd: {"dataset": "a.lg", "algorithm": a, "minWeight": tw, "status": "OK",
                                 "totalMs": ms, "totalSdMs": sd}
    rows = [row("WeLT", "1.500", "20.0", "1.0"), row("WeLT", "2.000", "30.0", "9.0"),
            row("GraMiFilter", "1.500", "40.0", "2.0"), row("GraMiFilter", "2.000", "50.0", "5.0"),
            row("WEGM", "1.500", "1.0", "0.9")]                                    # not in the group
    out, n = export(camp, r6, r5, rows)
    check("denominators", n, {"e1": 6, "r5s": 2, "e3": 4})
    check("e1: max is GraMi at 0.4", (out["e1.sd_rel.max"], out["e1.sd_rel.max.cell"]), (0.4, "a 10 GraMi"))
    check("e1: median of 0.05, 0.4, 0.02, 0.03, 0.09, 0.01", out["e1.sd_rel.median"], 0.04)
    check("e1: from 1,000 ms, two cells (999.9 left out), max 0.03",
          (out["e1.sd_rel.ge1s.cells"], out["e1.sd_rel.ge1s.max"], out["e1.sd_rel.ge1s.max.cell"]), (2, 0.03, "a 6 WeLT"))
    check("r5s: no cell from 1,000 ms, no ge1s key", any(k.startswith("r5s.sd_rel.ge1s") for k in out), False)
    check("r5s: equal maxima are both named", out["r5s.sd_rel.max.cell"], "a 10 w5 WEGM; a 10 w5 WeLT")
    check("e3: max 0.3 at tau_w 2, WEGM row left out", (out["e3.sd_rel.max"], out["e3.sd_rel.max.cell"]),
          (0.3, "a 2 WeLT"))
    check("e3: sd of a point", out["e3.a.p1.GraMiFilter.sd_ms"], 5.0)
    bad = dict(camp)
    del bad["e1.cell.a.10.WeLT.sd_ms"]
    refused("a completed cell without sd_ms", lambda: export(bad, r6, r5, rows))
    refused("a CSV median that differs from the exported one",
            lambda: export(camp, r6, r5, [dict(rows[0], totalMs="20.5")] + rows[1:]))
    refused("an Experiment 3 point without a row", lambda: export(camp, r6, r5, rows[1:]))
    refused("a key in two files", lambda: export(camp, {**r6, "e1.cell.a.10.WeLT.ms": 1.0}, r5, rows))
    print(f"export_time_spread self-test: {'OK' if fails == 0 else 'FAILED'} ({12 - fails}/12)")
    return 1 if fails else 0


def main():
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    ap = argparse.ArgumentParser()
    ap.add_argument("campaign")
    ap.add_argument("round6")
    ap.add_argument("round5")
    ap.add_argument("campaign_dir")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    with open(os.path.join(a.campaign_dir, "B5_tau_sweep.csv"), encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))
    out, n = export(values(a.campaign), values(a.round6), values(a.round5), rows)
    with open(a.out, "w", encoding="utf-8", newline="\n") as f:
        json.dump({"values": out}, f, indent=1, sort_keys=True)
        f.write("\n")
    for k in sorted(out):
        if ".sd_rel." in k:
            print(f"{k}\t{out[k]}")
    print(f"cells: {n}; keys written: {len(out)}")


if __name__ == "__main__":
    main()
