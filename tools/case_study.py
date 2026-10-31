#!/usr/bin/env python3
"""Per-pattern facts of a result set, recomputed from the definitions (standard library only).

For every pattern of a pattern dump (BenchmarkMain with DUMP_PATTERNS) the tool enumerates all
embeddings (injective, label-preserving maps of the pattern's vertices whose pattern edges are
graph edges) and reports:

  * images_G[v]     the number of distinct graph vertices that pattern vertex v maps to;
                    MNI_G = min over v
  * images_ge[v]    the same in G_{>=tau_w} (only edges of weight >= tau_w); its minimum is the
                    score of the ALL criterion (every edge of every counted occurrence heavy)
  * W               max over embeddings of the minimum edge weight (the bottleneck), compared
                    with the weight column of the dump
  * embeddings      number of embeddings; occurrences = number of distinct edge sets
  * one heaviest occurrence: graph vertex ids, their labels, the weight of every pattern edge

The graph is read like LgGraphReader: undirected, a reverse edge is merged and keeps the first
weight, self-loops are skipped. Edge labels are all 0 in the graphs this tool is used on; the
tool refuses a pattern with another edge label.

Usage:
  case_study.py <graph.lg> <patterns.tsv> <tau_w> <min_sup> --out <patterns.csv> [--limit-s 600]
  case_study.py --self-test
"""
import argparse
import csv
import sys
import time


def read_lg(path):
    labels, adj, weight = {}, {}, {}
    conflicts = 0
    with open(path, encoding="utf-8") as f:
        for line in f:
            p = line.split()
            if not p:
                continue
            if p[0] == "v":
                labels[int(p[1])] = int(p[2])
                adj.setdefault(int(p[1]), set())
            elif p[0] == "e":
                a, b, w = int(p[1]), int(p[2]), float(p[3])
                if a == b:
                    continue
                key = (min(a, b), max(a, b))
                if key in weight:
                    if abs(weight[key] - w) > 1e-9:
                        conflicts += 1
                    continue
                weight[key] = w
                adj[a].add(b)
                adj[b].add(a)
    return labels, adj, weight, conflicts


def parse_code(code):
    """'i j li le lj;...' -> (vertex labels by pattern index, list of edges (i, j))."""
    vlab, edges = {}, []
    for t in code.strip().rstrip(";").split(";"):
        i, j, li, le, lj = (int(x) for x in t.split())
        if le != 0:
            raise ValueError(f"edge label {le} in {code!r}: only edge label 0 is supported")
        for v, l in ((i, li), (j, lj)):
            if vlab.setdefault(v, l) != l:
                raise ValueError(f"vertex {v} has two labels in {code!r}")
        edges.append((i, j))
    return [vlab[v] for v in range(len(vlab))], edges


def embeddings(vlab, edges, labels, adj, ok_edge, deadline):
    """Yield every embedding as a tuple g[v]. ok_edge(a, b) filters graph edges."""
    n = len(vlab)
    pnb = {v: set() for v in range(n)}
    for i, j in edges:
        pnb[i].add(j)
        pnb[j].add(i)
    order, seen = [0], {0}
    while len(order) < n:  # BFS order: every vertex after the first has a mapped neighbour
        for v in list(order):
            for u in sorted(pnb[v]):
                if u not in seen:
                    seen.add(u)
                    order.append(u)
    back = {v: [u for u in pnb[v] if order.index(u) < order.index(v)] for v in order}
    m = [None] * n
    used = set()

    def rec(k):
        if time.monotonic() > deadline:
            raise TimeoutError
        if k == n:
            yield tuple(m)
            return
        v = order[k]
        if back[v]:
            cand = adj[m[back[v][0]]]
        else:
            cand = labels.keys()
        for g in cand:
            if g in used or labels[g] != vlab[v]:
                continue
            if all(g in adj[m[u]] and ok_edge(g, m[u]) for u in back[v]):
                m[v] = g
                used.add(g)
                yield from rec(k + 1)
                used.discard(g)
                m[v] = None

    yield from rec(0)


def facts(vlab, edges, labels, adj, weight, tau_w, limit_s):
    w = lambda a, b: weight[(min(a, b), max(a, b))]
    n = len(vlab)
    deadline = time.monotonic() + limit_s
    img = [set() for _ in range(n)]
    img_ge = [set() for _ in range(n)]
    emb = 0
    occ = set()
    best, best_map = None, None
    try:
        for e in embeddings(vlab, edges, labels, adj, lambda a, b: True, deadline):
            emb += 1
            occ.add(frozenset((min(e[i], e[j]), max(e[i], e[j])) for i, j in edges))
            for v in range(n):
                img[v].add(e[v])
            bott = min(w(e[i], e[j]) for i, j in edges)
            if bott >= tau_w:
                for v in range(n):
                    img_ge[v].add(e[v])
            if best is None or bott > best or (bott == best and e < best_map):
                best, best_map = bott, e
        complete = True
    except TimeoutError:
        complete = False
    return {
        "complete": complete,
        "images_G": [len(s) for s in img],
        "images_ge": [len(s) for s in img_ge],
        "embeddings": emb,
        "occurrences": len(occ),
        "W": best,
        "heaviest": best_map,
        "heaviest_weights": [w(best_map[i], best_map[j]) for i, j in edges] if best_map else [],
    }


def fmt(x):
    return f"{x:g}"


def run(args):
    labels, adj, weight, conflicts = read_lg(args.graph)
    rows = []
    with open(args.patterns, encoding="utf-8") as f:
        dump = list(csv.DictReader(f, delimiter="\t"))
    for d in dump:
        vlab, edges = parse_code(d["code"])
        r = facts(vlab, edges, labels, adj, weight, args.tau_w, args.limit_s)
        mni, all_score = min(r["images_G"]), min(r["images_ge"])
        rows.append({
            "code": d["code"],
            "vertices": len(vlab),
            "edges": len(edges),
            "vertex_labels": " ".join(map(str, vlab)),
            "pattern_edges": " ".join(f"{i}-{j}" for i, j in edges),
            "mni_G": mni,
            "W": fmt(r["W"]),
            "W_dump": d["weight"],
            "all_score": all_score,
            "embeddings": r["embeddings"],
            "occurrences": r["occurrences"],
            "complete": int(r["complete"]),
            "images_G": " ".join(map(str, r["images_G"])),
            "images_ge": " ".join(map(str, r["images_ge"])),
            "heaviest_vertices": " ".join(map(str, r["heaviest"])),
            "heaviest_labels": " ".join(str(labels[g]) for g in r["heaviest"]),
            "heaviest_edge_weights": " ".join(fmt(x) for x in r["heaviest_weights"]),
        })
    with open(args.out, "w", newline="", encoding="utf-8") as f:
        wr = csv.DictWriter(f, fieldnames=list(rows[0]))
        wr.writeheader()
        wr.writerows(rows)
    # checks against the definitions of the result set
    bad_w = [r["code"] for r in rows if abs(float(r["W"]) - float(r["W_dump"])) > 1e-6]
    bad_sup = [r["code"] for r in rows if r["mni_G"] < args.min_sup]
    bad_tw = [r["code"] for r in rows if float(r["W"]) < args.tau_w]
    in_all = [r["code"] for r in rows if r["all_score"] >= args.min_sup]
    incomplete = [r["code"] for r in rows if not r["complete"]]
    print(f"graph: {len(labels)} vertices, {len(weight)} undirected edges, {conflicts} reverse edges with another weight")
    print(f"patterns: {len(rows)}; enumeration complete for {len(rows) - len(incomplete)}/{len(rows)}")
    print(f"W recomputed = W of the dump: {len(rows) - len(bad_w)}/{len(rows)}")
    print(f"MNI_G >= {args.min_sup}: {len(rows) - len(bad_sup)}/{len(rows)}; W >= {fmt(args.tau_w)}: {len(rows) - len(bad_tw)}/{len(rows)}")
    print(f"ALL score >= {args.min_sup} (pattern kept by ALL): {len(in_all)}/{len(rows)}")
    return 1 if (bad_w or bad_sup or bad_tw or incomplete) else 0


def self_test():
    """A hand-made graph whose numbers are worked out in the comments."""
    import os
    import tempfile
    # labels: 1 2 1 2 1 ; edges 0-1 w5, 1-2 w1, 2-3 w7, 3-4 w2, 4-1 w9 (reverse 1-4 w3 is merged, keeps 9)
    g = "t # 0\nv 0 1\nv 1 2\nv 2 1\nv 3 2\nv 4 1\ne 0 1 5\ne 1 2 1\ne 2 3 7\ne 3 4 2\ne 4 1 9\ne 1 4 3\ne 2 2 8\n"
    fails = 0
    with tempfile.TemporaryDirectory() as t:
        p = os.path.join(t, "g.lg")
        open(p, "w").write(g)
        labels, adj, weight, conflicts = read_lg(p)

        def check(name, got, want):
            nonlocal fails
            print(("ok   " if got == want else "FAIL ") + f"{name}: got {got}, want {want}")
            fails += got != want

        check("reverse edge merged, first weight kept", (weight[(1, 4)], conflicts, len(weight)), (9.0, 1, 5))
        # pattern 1-2 (one edge): embeddings (0,1) (2,1) (2,3) (4,3) (4,1): label-1 images {0,2,4}, label-2 {1,3}
        vlab, edges = parse_code("0 1 1 0 2;")
        r = facts(vlab, edges, labels, adj, weight, 5, 10)
        check("edge 1-2: images in G", r["images_G"], [3, 2])
        check("edge 1-2: W is the heaviest edge", r["W"], 9.0)
        # in G>=5: edges 0-1 (5), 2-3 (7), 4-1 (9): label-1 {0,2,4}, label-2 {1,3}
        check("edge 1-2: images in G>=5 (weight equal to tau_w counts)", r["images_ge"], [3, 2])
        r = facts(vlab, edges, labels, adj, weight, 5.5, 10)
        check("edge 1-2: images in G>=5.5", r["images_ge"], [2, 2])
        check("edge 1-2: embeddings, occurrences", (r["embeddings"], r["occurrences"]), (5, 5))
        # pattern 1-2-1 (path, centre label 2): centre 1 with ends {0,2,4} -> 6 embeddings, 3 edge sets;
        # centre 3 with ends {2,4} -> 2 embeddings, 1 edge set. W: centre 1, ends 0,4 -> min(5,9) = 5;
        # centre 3 -> min(7,2) = 2
        vlab, edges = parse_code("0 1 1 0 2;1 2 2 0 1;")
        r = facts(vlab, edges, labels, adj, weight, 5, 10)
        check("path 1-2-1: embeddings, occurrences", (r["embeddings"], r["occurrences"]), (8, 4))
        check("path 1-2-1: W and its occurrence", (r["W"], r["heaviest"]), (5.0, (0, 1, 4)))
        check("path 1-2-1: images in G", r["images_G"], [3, 2, 3])
        check("path 1-2-1: images in G>=5", r["images_ge"], [2, 1, 2])
    print(f"case_study self-test: {'OK' if fails == 0 else 'FAILED'} ({10 - fails}/10)")
    return 1 if fails else 0


def main():
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    ap = argparse.ArgumentParser()
    ap.add_argument("graph")
    ap.add_argument("patterns")
    ap.add_argument("tau_w", type=float)
    ap.add_argument("min_sup", type=int)
    ap.add_argument("--out", required=True)
    ap.add_argument("--limit-s", type=float, default=600)
    sys.exit(run(ap.parse_args()))


if __name__ == "__main__":
    main()
