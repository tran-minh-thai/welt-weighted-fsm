#!/usr/bin/env python3
"""Write a copy of an .lg graph in which every vertex pair listed more than once keeps one edge line,
with the largest (max) or the smallest (min) of its listed weights. Other lines are copied unchanged.

LgGraphReader keeps the FIRST listing of a pair (a reverse listing is merged, a repeated listing is
dropped); this tool fixes the merged weight instead, to measure whether that choice changes a result.

Usage: merge_weights.py <in.lg> max|min <out.lg>
Prints the number of pairs listed more than once, how many carry different weights, and how many
edge lines change weight.
"""
import sys


def main():
    if len(sys.argv) != 4 or sys.argv[2] not in ("max", "min"):
        sys.exit(__doc__)
    src, mode, dst = sys.argv[1], sys.argv[2], sys.argv[3]
    pick = max if mode == "max" else min
    lines = open(src, encoding="utf-8").read().splitlines()
    weights = {}
    for line in lines:
        p = line.split()
        if p and p[0] == "e":
            a, b = int(p[1]), int(p[2])
            weights.setdefault((min(a, b), max(a, b)), []).append(p[3])
    multi = {k: w for k, w in weights.items() if len(w) > 1}
    differ = sum(1 for w in multi.values() if len({float(x) for x in w}) > 1)
    written, changed = set(), 0
    with open(dst, "w", encoding="utf-8", newline="\n") as out:
        for line in lines:
            p = line.split()
            if p and p[0] == "e":
                a, b = int(p[1]), int(p[2])
                key = (min(a, b), max(a, b))
                if key in multi:
                    if key in written:
                        continue
                    written.add(key)
                    w = pick(multi[key], key=float)
                    changed += float(w) != float(p[3])
                    line = f"e {p[1]} {p[2]} {w}"
            out.write(line + "\n")
    print(f"{src}: {len(multi)} pairs listed more than once, {differ} with different weights; "
          f"{mode}: {changed} first listings change weight -> {dst}")


if __name__ == "__main__":
    main()
