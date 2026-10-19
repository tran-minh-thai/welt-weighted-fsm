#!/usr/bin/env python3
"""Selections of the tau_w sweep (B5), read from result CSV files (standard library only).

Usage:
  python tools/b5_choose.py smallest-ok <csv> <dataset.lg> <algorithm>
      Smallest minSup with a status-OK row of <algorithm> on <dataset.lg>; prints nothing if none.
  python tools/b5_choose.py status <csv> <dataset.lg> <algorithm> <minSup>
      Status of the last row of that cell, or "none".

Rules of B5:
  minSup of the comparison sweep = smallest-ok of GraMi in the B1 range (GraMi is the
  tau_w -> 0 limit of the weighted problem, so every tau_w point is at most as hard); when
  GraMi completed nowhere in the range, the first value of a fixed extension list at which a
  GraMi probe completes within 60 s.
  minSup of the WeLT-only sweep = smallest-ok of WeLT in the B1 range.
"""
import csv
import os
import sys


def rows(path, dataset, algorithm):
    if not os.path.exists(path):
        return []
    with open(path, newline="", encoding="utf-8") as fh:
        return [r for r in csv.DictReader(fh) if r["dataset"] == dataset and r["algorithm"] == algorithm]


def main():
    a = sys.argv[1:]
    if len(a) == 4 and a[0] == "smallest-ok":
        ok = [int(r["minSup"]) for r in rows(a[1], a[2], a[3]) if r["status"] == "OK"]
        if ok:
            print(min(ok))
    elif len(a) == 5 and a[0] == "status":
        match = [r for r in rows(a[1], a[2], a[3]) if int(r["minSup"]) == int(a[4])]
        print(match[-1]["status"] if match else "none")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
