#!/usr/bin/env python3
"""Choose the WeLT voting weights on the development cells (rule fixed before any run).

Usage:  python tools/choose_voting.py <dev.csv> <calibration.txt> <out.json>

<dev.csv> holds BenchmarkMain rows of WeLT, one per (development cell, voter), voter written
"alpha;beta;gamma". The rule:
  1. a voter qualifies only if it completed (status OK) on every development cell;
  2. its score is the SUM over the development cells of `backtrack` (a deterministic count:
     identical on every run and every machine);
  3. the best voter has the smallest score; ties go to the voter listed first in the file;
  4. if the best score is less than 2% below the score of the degree-only voter (0;1;0), the
     degree-only voter is chosen.
<calibration.txt> is copied into the JSON so the record says how the cells were chosen.
Exit code 1 when no voter qualifies or the degree-only voter is missing.
"""
import csv
import json
import sys

DEGREE_ONLY = "0;1;0"


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    rows = [r for r in csv.DictReader(open(sys.argv[1], newline="", encoding="utf-8")) if r["algorithm"] == "WeLT"]
    cells = sorted({(r["dataset"], r["minSup"], r["minWeight"]) for r in rows})
    order, score, complete = [], {}, {}
    for r in rows:
        v = r["voter"]
        if v not in score:
            order.append(v)
            score[v] = 0
            complete[v] = set()
        if r["status"] == "OK":
            score[v] += int(r["backtrack"])
            complete[v].add((r["dataset"], r["minSup"], r["minWeight"]))
    qualified = [v for v in order if complete[v] == set(cells)]
    if not qualified:
        sys.exit("choose_voting: no voter completed every development cell")
    if DEGREE_ONLY not in qualified:
        sys.exit("choose_voting: the degree-only voter 0;1;0 did not complete every development cell")
    best = min(qualified, key=lambda v: (score[v], order.index(v)))
    gain = (score[DEGREE_ONLY] - score[best]) / score[DEGREE_ONLY] if score[DEGREE_ONLY] else 0.0
    chosen = best if gain >= 0.02 else DEGREE_ONLY
    record = {
        "voter": chosen.replace(";", ","),
        "worder": "on",
        "rule": "minimum summed backtrack over the development cells among voters that completed all of them; "
                "degree-only (0,1,0) unless the best is at least 2% lower; ties to the first in grid order",
        "developmentCells": [{"dataset": d, "minSup": s, "minWeight": w} for d, s, w in cells],
        "bestVoter": best.replace(";", ","),
        "relativeGainOverDegreeOnly": round(gain, 6),
        "scores": {v.replace(";", ","): score[v] for v in order},
        "qualified": [v.replace(";", ",") for v in qualified],
        "calibration": open(sys.argv[2], encoding="utf-8").read().splitlines(),
    }
    with open(sys.argv[3], "w", encoding="utf-8") as fh:
        json.dump(record, fh, indent=2)
        fh.write("\n")
    print(f"choose_voting: {len(qualified)}/{len(order)} voters qualified on {len(cells)} cells; best {best} "
          f"(gain {gain:.4%} over degree-only) -> chosen {chosen}")


if __name__ == "__main__":
    main()
