#!/usr/bin/env python3
"""Compare the result sets of two algorithms from pattern dumps (DUMP_PATTERNS).

Usage:  python3 tools/compare_patterns.py [--subset] <patterns-dir> <out.csv> [algoA] [algoB]

Defaults: algoA = EdgeTypeBound, algoB = WeLT (the same problem, so the sets must be equal). For every (dataset, minSup, minWeight) for which BOTH
files <dataset>_<algo>_<minSup>_<minWeight>.tsv exist (a file is written only for a completed
cell), one row:

  dataset, minSup, minWeight, algoA, algoB, R_A, R_B, onlyA, onlyB, sameSet,
  avgVertices_A, maxVertices_A, avgEdges_A, maxEdges_A, (same four for B)

Patterns are matched by canonical code. The script prints how many cells it could compare;
zero comparable cells is reported as such, not as agreement. Exit code 1 when a compared cell
has different result sets, 2 when no cell could be compared. With --subset the check is that
the result set of algoA is contained in the one of algoB (exit 1 when some cell has onlyA > 0).
Standard library only.
"""
import csv
import os
import re
import sys

NAME = re.compile(r"^(?P<ds>.+)_(?P<algo>[^_]+)_(?P<sup>\d+)_(?P<w>[^_]+)\.tsv$")


def load(path):
    with open(path, encoding="utf-8", newline="") as fh:
        rows = list(csv.DictReader(fh, delimiter="\t"))
    return {r["code"]: (int(r["vertices"]), int(r["edges"])) for r in rows}


def size_stats(patterns):
    if not patterns:
        return ("", "", "", "")
    v = [x[0] for x in patterns.values()]
    e = [x[1] for x in patterns.values()]
    return (f"{sum(v) / len(v):.3f}", max(v), f"{sum(e) / len(e):.3f}", max(e))


def main():
    args = [x for x in sys.argv[1:] if x != "--subset"]
    subset = len(args) != len(sys.argv) - 1
    if len(args) not in (2, 3, 4):
        sys.exit(__doc__)
    src, out = args[0], args[1]
    a = args[2] if len(args) > 2 else "EdgeTypeBound"
    b = args[3] if len(args) > 3 else "WeLT"
    files = {}
    for f in os.listdir(src):
        m = NAME.match(f)
        if m:
            files[(m["ds"], m["algo"], int(m["sup"]), m["w"])] = os.path.join(src, f)
    cells = sorted({(ds, sup, w) for (ds, algo, sup, w) in files})
    rows, differing, single = [], 0, 0
    for ds, sup, w in cells:
        fa, fb = files.get((ds, a, sup, w)), files.get((ds, b, sup, w))
        if not fa or not fb:
            single += 1
            continue
        pa, pb = load(fa), load(fb)
        only_a, only_b = len(pa.keys() - pb.keys()), len(pb.keys() - pa.keys())
        same = int(only_a == 0 and only_b == 0)
        differing += int(only_a > 0) if subset else 1 - same
        rows.append([ds, sup, w, a, b, len(pa), len(pb), only_a, only_b, same,
                     *size_stats(pa), *size_stats(pb)])
    header = ["dataset", "minSup", "minWeight", "algoA", "algoB", "R_A", "R_B", "onlyA", "onlyB", "sameSet",
              "avgVertices_A", "maxVertices_A", "avgEdges_A", "maxEdges_A",
              "avgVertices_B", "maxVertices_B", "avgEdges_B", "maxEdges_B"]
    with open(out, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(header)
        w.writerows(rows)
    relation = f"{a} contained in {b}" if subset else f"{a} vs {b}"
    failure = "not contained" if subset else "with different result sets"
    print(f"compare_patterns: {len(rows)} cell(s) compared ({relation}), {differing} {failure}; "
          f"{single} cell(s) have only one of the two files -> {out}")
    if not rows:
        print("compare_patterns: NOTHING COMPARED (no cell has both files)")
        sys.exit(2)
    sys.exit(1 if differing else 0)


if __name__ == "__main__":
    main()
