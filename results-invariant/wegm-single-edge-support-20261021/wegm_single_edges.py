#!/usr/bin/env python3
"""Can WEGM (Max method) return any pattern? A criterion computed from the graph files alone.

In WEGM's model an edge weight is part of the pattern (here its integer part, as for every dataset of
the campaign), and the support counts only occurrences with the same weights. Every edge of a frequent
pattern is a frequent 1-edge pattern, and the Max method keeps only patterns whose edges all weigh at
least tau_w. So WEGM returns a pattern iff some weighted edge type (label a, label b, integer weight)
with weight >= tau_w has MNI >= minSup; the 1-edge pattern of that type is then itself a result.

The MNI of a weighted edge type is the smaller image of its two pattern vertices; with equal labels
every endpoint is an image of both vertices. Duplicate edges (u,v), (v,u) are merged.

Usage (from the repository root):
  python results-invariant/wegm-single-edge-support-20261021/wegm_single_edges.py [B1_efficiency.csv]
With a B1 file, the criterion is compared with the WEGM result count R of every completed cell; the
script exits 1 on a disagreement or when no cell could be compared.
"""
import collections
import csv
import os
import sys

TAU_W = {"citeseer": 96, "email_eu": 336, "github": 30, "lastfm": 40, "mico": 100, "string_ecoli": 750}


def largest_heavy_mni(ds, tw):
    lab, edges = {}, {}
    with open(os.path.join("datasets", ds + ".lg"), encoding="utf-8") as fh:
        for line in fh:
            t = line.split()
            if not t or t[0] not in ("v", "e"):
                continue
            if t[0] == "v":
                lab[int(t[1])] = int(t[2])
            else:
                u, v = int(t[1]), int(t[2])
                edges[(min(u, v), max(u, v))] = float(t[3])
    img = collections.defaultdict(lambda: (set(), set()))
    for (u, v), w in edges.items():
        a, b = lab[u], lab[v]
        if a > b:
            u, v, a, b = v, u, b, a
        k = (a, b, int(w))
        if a == b:
            img[k][0].update((u, v))
            img[k][1].update((u, v))
        else:
            img[k][0].add(u)
            img[k][1].add(v)
    heavy = [min(len(x), len(y)) for (a, b, w), (x, y) in img.items() if w >= tw]
    weights = {int(w) for w in edges.values()}
    return len(edges), len(weights), len(heavy), max(heavy) if heavy else 0


def main():
    best = {}
    print("dataset,edges,distinctIntegerWeights,tauW,heavyWeightedEdgeTypes,largestHeavyMNI")
    for ds, tw in TAU_W.items():
        e, nw, nh, m = largest_heavy_mni(ds, tw)
        best[ds] = m
        print(f"{ds},{e},{nw},{tw},{nh},{m}")
    if len(sys.argv) < 2:
        return
    compared, wrong = 0, []
    with open(sys.argv[1], newline="", encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            if r["algorithm"] != "WEGM" or r["status"] != "OK":
                continue
            ds = r["dataset"][:-3]
            predicted = best[ds] >= int(r["minSup"])
            compared += 1
            if predicted != (int(r["R"]) > 0):
                wrong.append((ds, r["minSup"], r["R"], best[ds]))
    print(f"WEGM cells compared: {compared}; criterion and result count disagree in {len(wrong)}: {wrong}")
    sys.exit(1 if wrong or compared == 0 else 0)


if __name__ == "__main__":
    main()
