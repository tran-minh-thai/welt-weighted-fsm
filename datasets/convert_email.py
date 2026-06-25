#!/usr/bin/env python3
"""Convert email-Eu-core (SNAP, temporal) to .lg with native, right-skewed edge weights.

- Edge weight: the NUMBER OF EMAILS exchanged between two people (counted from the
  temporal edition) — native, integer, strongly RIGHT-SKEWED (most pairs exchange few
  emails, a few pairs exchange many), making the weight filter SELECTIVE.
- Keep only pairs with >= THRESHOLD emails to SPARSIFY (email graphs are dense) and to
  drop noise from one-off contacts.
- Vertex label: the native DEPARTMENT (42 classes) from the department labels.
- Edges carry no structural label.

Usage: python3 convert_email.py <email_temporal.txt> <dept_labels.txt> <threshold> <out.lg>
"""
import sys
from collections import defaultdict, Counter


def main():
    email_path, dept_path, thr_s, out_path = sys.argv[1:5]
    thr = int(thr_s)

    dept = {}
    with open(dept_path) as f:
        for line in f:
            a, b = line.split()
            dept[int(a)] = int(b)

    cnt = defaultdict(int)
    with open(email_path) as f:
        for line in f:
            p = line.split()
            a, b = int(p[0]), int(p[1])
            if a == b:
                continue
            lo, hi = (a, b) if a < b else (b, a)
            cnt[(lo, hi)] += 1

    # filter + dense id mapping
    idmap = {}
    def dense(x):
        if x not in idmap:
            idmap[x] = len(idmap)
        return idmap[x]

    edges = []
    for (lo, hi), c in cnt.items():
        if c >= thr:
            edges.append((dense(lo), dense(hi), c, lo, hi))

    n = len(idmap)
    inv = {v: k for k, v in idmap.items()}
    labels = [dept.get(inv[i], 0) for i in range(n)]

    wmin = min(e[2] for e in edges); wmax = max(e[2] for e in edges)
    wsum = sum(e[2] for e in edges)
    with open(out_path, 'w') as out:
        out.write('t # 1\n')
        for i in range(n):
            out.write('v %d %d\n' % (i, labels[i]))
        for a, b, c, _lo, _hi in edges:
            out.write('e %d %d %d\n' % (a, b, c))

    print('%s: %d vertices, %d edges (>=%d emails) | w(email count)[min=%d max=%d mean=%.1f] | #labels=%d'
          % (out_path, n, len(edges), thr, wmin, wmax, wsum / len(edges), len(set(labels))))


if __name__ == '__main__':
    main()
