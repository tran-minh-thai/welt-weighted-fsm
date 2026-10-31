#!/usr/bin/env python3
"""Summarise a run of the MiCo merge-weight probe: the same cells on mico.lg and on the two copies written
by tools/merge_weights.py (every pair listed twice keeps its larger or its smaller weight).

    summarise_merge_probe.py <probe dir> <graph.lg> <out dir>

<probe dir> holds bench_<graph>_<minSup>.txt (BenchmarkMain output with BENCH_CSV), all_<graph>_<minSup>.txt
(ResumMain definition) and patterns/ (DUMP_PATTERNS). Writes <out dir>/counts.csv (one row per graph, cell
and algorithm) and <out dir>/numbers.json with the keys
  data.mico.merge_{max,min}.changed_cells   cells where R, N, M, backtracking nodes or a result-set file
                                            differ from mico.lg (any algorithm, ALL included)
  data.mico.merge.cells                     cells compared
  data.mico.merge.pairs_two_weights         vertex pairs listed twice with different weights
  data.mico.merge.pairs_on_result_labels    of those, pairs whose label pair is an edge type of a pattern
                                            in the result sets on mico.lg
Exits 1 when a run is missing or not OK.
"""
import csv
import io
import json
import os
import sys

GRAPHS = ("mico", "mico_merge_max", "mico_merge_min")


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    probe, lg, out = sys.argv[1:]
    rows, problems = [], []
    cells = sorted({f.split("_")[-1][:-4] for f in os.listdir(probe) if f.startswith("bench_mico_")}, key=int)
    for g in GRAPHS:
        for s in cells:
            p = os.path.join(probe, f"bench_{g}_{s}.txt")
            if not os.path.isfile(p):
                problems.append(f"missing {p}")
                continue
            csv_lines = [l[4:] for l in open(p, encoding="utf-8") if l.startswith("CSV,")]
            for r in csv.DictReader(io.StringIO("".join(csv_lines))):
                if r["status"] != "OK":
                    problems.append(f"{g} {s} {r['algorithm']}: status {r['status']}")
                rows.append({"graph": g, "minSup": s, "algorithm": r["algorithm"], "status": r["status"],
                             "R": r["R"], "N": r["N"], "M": r["M"], "backtrack": r["backtrack"]})
            a = os.path.join(probe, f"all_{g}_{s}.txt")
            if os.path.isfile(a):
                rows.append({"graph": g, "minSup": s, "algorithm": "ALL", "status": "OK",
                             "R": open(a, encoding="utf-8").read().split(":")[-1].split()[0], "N": "", "M": "", "backtrack": ""})
    key = lambda r: (r["minSup"], r["algorithm"])
    base = {key(r): r for r in rows if r["graph"] == "mico"}
    pats = os.path.join(probe, "patterns")
    values = {}
    for g in GRAPHS[1:]:
        changed = set()
        for r in rows:
            if r["graph"] != g:
                continue
            b = base.get(key(r))
            if b is None or any(r[k] != b[k] for k in ("status", "R", "N", "M", "backtrack")):
                changed.add(r["minSup"])
        for f in os.listdir(pats):
            if f.startswith("mico_") and not f.startswith("mico_merge"):
                other = os.path.join(pats, f"{g}_{f[len('mico_'):]}")
                if not os.path.isfile(other) or open(other, "rb").read() != open(os.path.join(pats, f), "rb").read():
                    changed.add(f.split("_")[-2])
        values[f"data.mico.merge_{g.split('_')[-1]}.changed_cells"] = len(changed)
    values["data.mico.merge.cells"] = len(cells)
    # vertex pairs listed twice with different weights, and whether their label pair occurs in a result pattern
    lab, w = {}, {}
    for line in open(lg, encoding="utf-8"):
        p = line.split()
        if p and p[0] == "v":
            lab[int(p[1])] = int(p[2])
        elif p and p[0] == "e":
            a, b = int(p[1]), int(p[2])
            w.setdefault((min(a, b), max(a, b)), set()).add(float(p[3]))
    two = [k for k, v in w.items() if len(v) > 1]
    types = set()
    for f in os.listdir(pats):
        if f.startswith("mico_") and not f.startswith("mico_merge"):
            for line in list(open(os.path.join(pats, f), encoding="utf-8"))[1:]:
                for t in line.split("\t")[0].strip().rstrip(";").split(";"):
                    i, j, li, le, lj = (int(x) for x in t.split())
                    types.add(tuple(sorted((li, lj))))
    values["data.mico.merge.pairs_two_weights"] = len(two)
    values["data.mico.merge.pairs_on_result_labels"] = sum(1 for a, b in two if tuple(sorted((lab[a], lab[b]))) in types)
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "counts.csv"), "w", newline="", encoding="utf-8") as f:
        wr = csv.DictWriter(f, fieldnames=list(rows[0]))
        wr.writeheader()
        wr.writerows(rows)
    with open(os.path.join(out, "numbers.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump({"values": values}, f, indent=1, sort_keys=True)
        f.write("\n")
    for k, v in sorted(values.items()):
        print(f"{k} = {v}")
    print(f"rows: {len(rows)} ({len(cells)} cells x {len(GRAPHS)} graphs)")
    for p in problems:
        print("ERROR: " + p, file=sys.stderr)
    sys.exit(1 if problems or not rows else 0)


if __name__ == "__main__":
    main()
