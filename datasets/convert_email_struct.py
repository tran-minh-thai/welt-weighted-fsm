#!/usr/bin/env python3
"""Convert email-Eu-core (SNAP, temporal) to weighted .lg format with STRUCTURAL vertex labels.

Same vertices, vertex ids, edges and edge weights as convert_email.py (weight = number of
emails between two people, pairs with at least <threshold> emails), but the vertex label is
the degree class of the vertex (the rule of convert_musae_struct.py) instead of the department.
The department labels are never read, so the graph carries no information about the
node-classification label (used by the downstream experiment).

Usage: python3 convert_email_struct.py <email_temporal.txt> <threshold> <out.lg> [--check-against <lg>]

--check-against compares the result with an .lg file built by convert_email.py from the same
raw file and threshold: the 't' line, the vertex ids and every 'e' line must be identical; only
the vertex labels may differ. Exits 1 on any difference.
"""
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from convert_musae_struct import check_against, degree_classes  # noqa: E402


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
    email_path, thr_s, out_path = args
    thr = int(thr_s)

    # the pair counts and the id mapping of convert_email.py, in the same order
    cnt = defaultdict(int)
    with open(email_path, encoding='utf-8') as f:
        for line in f:
            p = line.split()
            a, b = int(p[0]), int(p[1])
            if a == b:
                continue
            lo, hi = (a, b) if a < b else (b, a)
            cnt[(lo, hi)] += 1
    idmap = {}

    def dense(x):
        if x not in idmap:
            idmap[x] = len(idmap)
        return idmap[x]

    edges = [(dense(lo), dense(hi), c) for (lo, hi), c in cnt.items() if c >= thr]
    n = len(idmap)
    deg, cuts, labels = degree_classes(n, [(a, b) for a, b, _c in edges])
    with open(out_path, 'w', newline='\n', encoding='utf-8') as out:
        out.write('t # 1\n')
        for i in range(n):
            out.write('v %d %d\n' % (i, labels[i]))
        for a, b, c in edges:
            out.write('e %d %d %d\n' % (a, b, c))
    sizes = [labels.count(k) for k in range(5)]
    print('%s: %d vertices, %d edges (>=%d emails) | degree cut points %s | class sizes %s'
          % (out_path, n, len(edges), thr, cuts, sizes))
    if ref is not None and not check_against(out_path, ref):
        sys.exit(1)


if __name__ == '__main__':
    main()
