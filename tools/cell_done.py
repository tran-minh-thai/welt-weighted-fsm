#!/usr/bin/env python3
"""Exit 0 when a result CSV already holds a row for a cell, 1 otherwise (resume support).

Usage:  python tools/cell_done.py <csv> <key=value> ...

A row matches when every key equals the given value. Numbers are compared numerically, after
rounding the given value to the decimals the CSV wrote (minWeight 96 = 96.000, 5.7544 = 5.754: the
runners print three decimals); a value written without decimals is compared exactly. `voter` is compared as three numbers, separators ',' or ';'
("0.2,0.8,0" = "0.2;0.8;0.0"). Any status counts as done (OK, TO, OOM, CRASH): a cell is
re-run only when its process left no row. A missing or empty CSV means not done.
"""
import csv
import os
import sys


def same(a, b, key):
    if key == "voter":
        try:
            return [float(x) for x in a.replace(";", ",").split(",")] == \
                   [float(x) for x in b.replace(";", ",").split(",")]
        except ValueError:
            return False
    try:
        x, y = float(a), float(b)
    except ValueError:
        return a == b
    t = a.strip()
    if "." in t and "e" not in t.lower():
        return round(y, len(t.split(".")[1])) == x
    return x == y


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    path = sys.argv[1]
    want = dict(arg.split("=", 1) for arg in sys.argv[2:])
    if not os.path.exists(path) or os.path.getsize(path) == 0:
        sys.exit(1)
    with open(path, newline="", encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            if all(k in row and same(row[k], v, k) for k, v in want.items()):
                sys.exit(0)
    sys.exit(1)


if __name__ == "__main__":
    main()
