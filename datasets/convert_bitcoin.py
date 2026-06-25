#!/usr/bin/env python3
"""Convert Bitcoin-OTC (SNAP soc-sign) to .lg with native, widely spread edge weights.

- Edge weight: the original TRUST rating (rating in [-10,10]) SHIFTED by +11 -> [1,21]
  (positive for the bottleneck-weight model; ordering and spread preserved). Directed
  edges are collapsed to undirected using the MEAN of the two-way ratings. The weights
  are native and widely spread (tails reaching +/-10), making the P2 filter highly selective.
- Vertex label: 5 QUANTILE buckets by DEGREE (independent of weight) — a user's "activity class".
- Edges carry no structural label.

Usage: python3 convert_bitcoin.py <otc.csv> <out.lg>
"""
import csv
import sys
from collections import defaultdict

SHIFT = 11      # rating+11 => [1,21]
NLABEL = 5      # number of degree quantile buckets


def main():
    in_path, out_path = sys.argv[1:3]

    # collapse to undirected: (min,max) -> [ratings]
    und = defaultdict(list)
    with open(in_path) as f:
        for src, tgt, rating, _t in csv.reader(f):
            a, b = int(src), int(tgt)
            if a == b:
                continue
            lo, hi = (a, b) if a < b else (b, a)
            und[(lo, hi)].append(int(rating))

    # dense id mapping
    idmap = {}
    def dense(x):
        if x not in idmap:
            idmap[x] = len(idmap)
        return idmap[x]

    edges = []
    deg = defaultdict(int)
    for (lo, hi), ratings in und.items():
        a, b = dense(lo), dense(hi)
        w = sum(ratings) / len(ratings) + SHIFT   # mean rating, shifted positive
        edges.append((a, b, w))
        deg[a] += 1
        deg[b] += 1

    n = len(idmap)
    # label = degree quantile bucket
    order = sorted(range(n), key=lambda v: deg.get(v, 0))
    label = [0] * n
    for rank, v in enumerate(order):
        label[v] = min(NLABEL - 1, rank * NLABEL // n)

    wmin = min(e[2] for e in edges); wmax = max(e[2] for e in edges)
    wsum = sum(e[2] for e in edges)
    with open(out_path, 'w') as out:
        out.write('t # 1\n')
        for v in range(n):
            out.write('v %d %d\n' % (v, label[v]))
        for a, b, w in edges:
            out.write('e %d %d %.3f\n' % (a, b, w))

    from collections import Counter
    print('%s: %d vertices, %d edges | w(trust+11)[min=%.1f max=%.1f mean=%.2f] | degree-label distribution=%s'
          % (out_path, n, len(edges), wmin, wmax, wsum / len(edges),
             dict(sorted(Counter(label).items()))))


if __name__ == '__main__':
    main()
