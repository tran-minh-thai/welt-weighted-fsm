#!/usr/bin/env python3
"""Convert STRING PPI (filtered to combined_score >= threshold) to .lg with native edge weights.

- Edge weight: STRING's native combined_score (interaction confidence, 150-999) —
  native weights, not assigned or fabricated.
- Vertex label: collapse Subcellular localization (COMPARTMENTS, GO) into the 5 standard
  gram-negative bacterial compartments by priority (outer -> inner): outer membrane >
  periplasm > membrane/inner > cytoplasm/cytosol > unknown. Categorical with few classes,
  giving a good constraint.
- Edges carry no structural label (token 3 = weight).

Usage: python3 convert_string.py <edges_filtered.txt> <enrichment.txt> <out.lg>
"""
import sys

# (keyword, label) in DESCENDING PRIORITY — a protein takes the highest-priority label it matches
PRIORITY = [
    ('outer membrane', 4),
    ('periplasm', 3),
    ('plasma membrane', 2), ('inner membrane', 2), ('membrane', 2),
    ('cytosol', 1), ('cytoplasm', 1), ('intracellular', 1),
]
UNKNOWN = 0


def main():
    edges_path, enrich_path, out_path = sys.argv[1:4]

    # collect localization terms per protein
    loc_terms = {}
    with open(enrich_path) as f:
        next(f)
        for line in f:
            p = line.rstrip('\n').split('\t')
            if len(p) >= 4 and p[1] == 'Subcellular localization (COMPARTMENTS)':
                loc_terms.setdefault(p[0], []).append(p[3].lower())

    def label_of(protein):
        terms = loc_terms.get(protein, [])
        joined = ' ; '.join(terms)
        for kw, lab in PRIORITY:
            if kw in joined:
                return lab
        return UNKNOWN

    # read filtered edges (protein1 protein2 score), dense id mapping
    idmap = {}
    edges = []
    with open(edges_path) as f:
        for line in f:
            a, b, s = line.split()
            for x in (a, b):
                if x not in idmap:
                    idmap[x] = len(idmap)
            edges.append((idmap[a], idmap[b], int(s)))

    n = len(idmap)
    inv = {v: k for k, v in idmap.items()}
    labels = [label_of(inv[i]) for i in range(n)]

    smin = min(e[2] for e in edges); smax = max(e[2] for e in edges)
    ssum = sum(e[2] for e in edges)
    with open(out_path, 'w') as out:
        out.write('t # 1\n')
        for i in range(n):
            out.write('v %d %d\n' % (i, labels[i]))
        for a, b, s in edges:
            out.write('e %d %d %d\n' % (a, b, s))

    from collections import Counter
    dist = Counter(labels)
    print('%s: %d vertices, %d edges | w(score)[min=%d max=%d mean=%.1f] | label(0..4) distribution=%s'
          % (out_path, n, len(edges), smin, smax, ssum / len(edges), dict(sorted(dist.items()))))


if __name__ == '__main__':
    main()
