#!/usr/bin/env python3
"""Convert a MUSAE dataset to weighted .lg format with STRUCTURAL vertex labels.

Same vertices, edges and edge weights as convert_musae.py (w(u,v) = Jaccard(F_u, F_v) x 100,
written with the same formatting), but the vertex label is the degree class of the vertex
instead of the class target. The target file is never read, so the graph carries no
information about the node-classification label (used by the downstream experiment).

Degree classes (5 classes from the degree quintiles, ties resolved by value):
    q_k = sorted_degrees[floor(k * n / 5)]   for k = 1..4
    label(v) = |{k : deg(v) > q_k}|          in {0, ..., 4}
Vertices of equal degree always share a class; the classes are therefore not of equal size
when the degree distribution has ties (they are printed).

Usage: python3 convert_musae_struct.py <edges.csv> <features.json> <out.lg> [--check-against <lg>]

--check-against compares the result with an .lg file built by convert_musae.py from the same
raw files: the 't' line, the vertex ids and every 'e' line must be identical; only the
vertex labels may differ. Exits 1 on any difference.
"""
import csv
import json
import sys


def degree_classes(n, edges):
    deg = [0] * n
    for a, b in edges:
        deg[a] += 1
        deg[b] += 1
    s = sorted(deg)
    cuts = [s[(k * n) // 5] for k in range(1, 5)]
    return deg, cuts, [sum(1 for q in cuts if d > q) for d in deg]


def read_lg(path):
    t, v, e = [], [], []
    with open(path, newline='') as f:
        for line in f:
            line = line.rstrip('\r\n')
            if line.startswith('t'):
                t.append(line)
            elif line.startswith('v'):
                v.append(line.split())
            elif line.startswith('e'):
                e.append(line)
    return t, v, e


def check_against(out_path, ref_path):
    t1, v1, e1 = read_lg(out_path)
    t2, v2, e2 = read_lg(ref_path)
    problems = []
    if t1 != t2:
        problems.append('t lines differ: %r vs %r' % (t1, t2))
    if [x[1] for x in v1] != [x[1] for x in v2]:
        problems.append('vertex ids differ (%d vs %d vertices)' % (len(v1), len(v2)))
    if e1 != e2:
        diff = sum(1 for a, b in zip(e1, e2) if a != b) + abs(len(e1) - len(e2))
        problems.append('edge lines differ: %d vs %d lines, %d positions differ' % (len(e1), len(e2), diff))
    same_label = sum(1 for a, b in zip(v1, v2) if a[2] == b[2])
    print('check against %s: %d vertices, %d edge lines compared byte by byte; %d vertices share their label'
          % (ref_path, len(v2), len(e2), same_label))
    for p in problems:
        print('ERROR: ' + p)
    return not problems


def main():
    args = sys.argv[1:]
    ref = None
    if '--check-against' in args:
        i = args.index('--check-against')
        ref = args[i + 1]
        args = args[:i] + args[i + 2:]
    if len(args) != 3:
        print(__doc__)
        sys.exit(2)
    edges_path, feats_path, out_path = args

    feats = json.load(open(feats_path))
    feats = {int(k): set(v) for k, v in feats.items()}

    edges = []
    with open(edges_path) as f:
        r = csv.reader(f)
        next(r)  # header
        for a, b in r:
            edges.append((int(a), int(b)))

    ids = set(feats) | {x for e in edges for x in e}
    n = max(ids) + 1
    if len(ids) != n:
        print('ERROR: vertex ids are not contiguous (%d ids, max %d)' % (len(ids), n - 1))
        sys.exit(1)
    if len({(min(a, b), max(a, b)) for a, b in edges}) != len(edges) or any(a == b for a, b in edges):
        print('ERROR: the edge list has duplicates or self-loops; degrees would not match the graph')
        sys.exit(1)

    deg, cuts, labels = degree_classes(n, edges)

    with open(out_path, 'w', newline='\n') as out:
        out.write('t # 1\n')
        for v in range(n):
            out.write('v %d %d\n' % (v, labels[v]))
        for a, b in edges:
            fa, fb = feats.get(a, set()), feats.get(b, set())
            uni = len(fa | fb)
            j = (len(fa & fb) / uni) if uni else 0.0
            out.write('e %d %d %.4f\n' % (a, b, j * 100.0))

    sizes = [labels.count(c) for c in range(5)]
    print('%s: %d vertices, %d edges | degree cut points %s | class sizes %s'
          % (out_path, n, len(edges), cuts, sizes))
    if ref is not None and not check_against(out_path, ref):
        sys.exit(1)


if __name__ == '__main__':
    main()
