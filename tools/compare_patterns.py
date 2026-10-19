#!/usr/bin/env python3
"""Compare the result sets of two algorithms from pattern dumps (DUMP_PATTERNS).

Usage:  python3 tools/compare_patterns.py <patterns-dir> <out.csv> [algoA] [algoB]

Defaults: algoA = WEGM, algoB = WeLT. For every (dataset, minSup, minWeight) for which BOTH
files <dataset>_<algo>_<minSup>_<minWeight>.tsv exist (a file is written only for a completed
cell), one row:

  dataset, minSup, minWeight, algoA, algoB, R_A, R_B, onlyA, onlyB, sameSet,
  avgVertices_A, maxVertices_A, avgEdges_A, maxEdges_A, (same four for B)

Patterns are matched by canonical code. The script prints how many cells it could compare;
zero comparable cells is reported as such, not as agreement. Exit code 1 when a compared cell
has different result sets, 2 when no cell could be compared. Standard library only.
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
    if len(sys.argv) not in (3, 4, 5):
        sys.exit(__doc__)
    src, out = sys.argv[1], sys.argv[2]
    a = sys.argv[3] if len(sys.argv) > 3 else "WEGM"
    b = sys.argv[4] if len(sys.argv) > 4 else "WeLT"
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
        differing += 1 - same
        rows.append([ds, sup, w, a, b, len(pa), len(pb), only_a, only_b, same,
                     *size_stats(pa), *size_stats(pb)])
    header = ["dataset", "minSup", "minWeight", "algoA", "algoB", "R_A", "R_B", "onlyA", "onlyB", "sameSet",
              "avgVertices_A", "maxVertices_A", "avgEdges_A", "maxEdges_A",
              "avgVertices_B", "maxVertices_B", "avgEdges_B", "maxEdges_B"]
    with open(out, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(header)
        w.writerows(rows)
    print(f"compare_patterns: {len(rows)} cell(s) compared ({a} vs {b}), {differing} with different result sets; "
          f"{single} cell(s) have only one of the two files -> {out}")
    if not rows:
        print("compare_patterns: NOTHING COMPARED (no cell has both files)")
        sys.exit(2)
    sys.exit(1 if differing else 0)


if __name__ == "__main__":
    main()
