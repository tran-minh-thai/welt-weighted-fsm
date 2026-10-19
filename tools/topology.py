#!/usr/bin/env python3
"""Topology and weight statistics of every .lg graph, one CSV row per file.

Usage:  python3 tools/topology.py <datasets-dir> <out.csv>

The graph is read the way welt.core.LgGraphReader reads it: undirected; a self-loop is
skipped; (u,v) and (v,u) are merged into one edge that keeps the FIRST weight seen; an exact
duplicate line is dropped. Columns:

  dataset, vertices, edges, density (2|E| / (|V|(|V|-1))), avgDegree, maxDegree,
  degreeCV (population standard deviation / mean of the degree), isolatedVertices,
  vertexLabels (distinct), weightMin, weightMax, weightMedian, weightSkewness (Fisher-Pearson
  g1 = m3 / m2^1.5, population moments), zeroWeightEdges, distinctWeights, integerWeights.

Standard library only. These are properties of the data, independent of the machine.
"""
import csv
import math
import os
import sys


def read_lg(path):
    labels = {}
    edges = {}          # (a, b) with a < b -> weight (first seen)
    directed = set()
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            tok = line.split()
            if not tok:
                continue
            if tok[0] == "v":
                labels[int(tok[1])] = int(tok[2])
            elif tok[0] == "e":
                u, v, w = int(tok[1]), int(tok[2]), float(tok[3])
                if u == v:
                    continue
                if (u, v) in directed:
                    continue
                directed.add((u, v))
                key = (min(u, v), max(u, v))
                if key not in edges:
                    edges[key] = w
    return labels, edges


def median(sorted_values):
    n = len(sorted_values)
    if n == 0:
        return float("nan")
    mid = n // 2
    return sorted_values[mid] if n % 2 else (sorted_values[mid - 1] + sorted_values[mid]) / 2.0


def stats(path):
    labels, edges = read_lg(path)
    n, m = len(labels), len(edges)
    degree = {v: 0 for v in labels}
    for a, b in edges:
        degree[a] = degree.get(a, 0) + 1
        degree[b] = degree.get(b, 0) + 1
    degs = list(degree.values())
    mean_deg = sum(degs) / n if n else 0.0
    sd_deg = math.sqrt(sum((d - mean_deg) ** 2 for d in degs) / n) if n else 0.0
    ws = sorted(edges.values())
    if ws:
        mu = sum(ws) / m
        m2 = sum((w - mu) ** 2 for w in ws) / m
        m3 = sum((w - mu) ** 3 for w in ws) / m
        skew = m3 / m2 ** 1.5 if m2 > 0 else 0.0
    else:
        skew = float("nan")
    return {
        "dataset": os.path.basename(path),
        "vertices": n,
        "edges": m,
        "density": (2.0 * m / (n * (n - 1))) if n > 1 else 0.0,
        "avgDegree": mean_deg,
        "maxDegree": max(degs) if degs else 0,
        "degreeCV": (sd_deg / mean_deg) if mean_deg > 0 else 0.0,
        "isolatedVertices": sum(1 for d in degs if d == 0),
        "vertexLabels": len(set(labels.values())),
        "weightMin": ws[0] if ws else float("nan"),
        "weightMax": ws[-1] if ws else float("nan"),
        "weightMedian": median(ws),
        "weightSkewness": skew,
        "zeroWeightEdges": sum(1 for w in ws if w == 0.0),
        "distinctWeights": len(set(ws)),
        "integerWeights": int(all(float(w).is_integer() for w in ws)),
    }


def fmt(v):
    return f"{v:.6g}" if isinstance(v, float) else str(v)


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    src, out = sys.argv[1], sys.argv[2]
    files = sorted(f for f in os.listdir(src) if f.endswith(".lg"))
    if not files:
        sys.exit(f"no .lg file in {src}")
    rows = [stats(os.path.join(src, f)) for f in files]
    with open(out, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        w.writeheader()
        for r in rows:
            w.writerow({k: fmt(v) for k, v in r.items()})
    print(f"topology.py: {len(rows)} graphs -> {out}")


if __name__ == "__main__":
    main()
