#!/usr/bin/env python3
"""Convert a MUSAE dataset (lastfm/github) to weighted .lg format.

- Vertex label: taken from target.csv (id,target) — the node's native class label.
- Edge weight: w(u,v) = Jaccard(F_u, F_v) x 100, where F is the feature set in
  features.json (native). Same "similarity" semantics as CiteSeer; the features are
  native, so these are NOT fabricated weights (see similarity-graph construction).
- Edges carry no structural label (token 3 = weight; the reader uses CONSTANT_UNLABELED).

Usage: python3 convert_musae.py <edges.csv> <features.json> <target.csv> <out.lg>
"""
import json
import csv
import sys


def main():
    edges_path, feats_path, target_path, out_path = sys.argv[1:5]

    feats = json.load(open(feats_path))
    feats = {int(k): set(v) for k, v in feats.items()}

    labels = {}
    with open(target_path) as f:
        r = csv.reader(f)
        next(r)  # header (id,target) or (id,name,ml_target)
        for row in r:
            labels[int(row[0])] = int(row[-1])  # label = LAST column

    n = len(labels)
    edges = []
    with open(edges_path) as f:
        r = csv.reader(f)
        next(r)  # header
        for a, b in r:
            edges.append((int(a), int(b)))

    wmin, wmax, wsum, zero = 1e9, -1e9, 0.0, 0
    out_edges = []
    for a, b in edges:
        fa, fb = feats.get(a, set()), feats.get(b, set())
        uni = len(fa | fb)
        j = (len(fa & fb) / uni) if uni else 0.0
        w = j * 100.0
        if w == 0.0:
            zero += 1
        wmin, wmax, wsum = min(wmin, w), max(wmax, w), wsum + w
        out_edges.append((a, b, w))

    with open(out_path, 'w') as out:
        out.write('t # 1\n')
        for v in range(n):
            out.write('v %d %d\n' % (v, labels.get(v, 0)))
        for a, b, w in out_edges:
            out.write('e %d %d %.4f\n' % (a, b, w))

    nlab = len(set(labels.values()))
    print('%s: %d vertices, %d edges, %d vertex labels | w[min=%.2f max=%.2f mean=%.4f] | edges with w=0: %d (%.1f%%)'
          % (out_path, n, len(out_edges), nlab, wmin, wmax, wsum / len(out_edges),
             zero, 100 * zero / len(out_edges)))


if __name__ == '__main__':
    main()
