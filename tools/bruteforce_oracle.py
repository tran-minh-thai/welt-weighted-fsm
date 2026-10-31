#!/usr/bin/env python3
"""Brute-force oracle for the result set of WeLT on small graphs (standard library only; no code shared
with the Java miner).

For a graph G, a support threshold tau_s and a weight threshold tau_w, the oracle computes the result
set from the definitions alone:

  * candidates: every connected edge subset of G, identified up to isomorphism by a canonical form
    computed over all label-preserving vertex orders;
  * MNI(S): over all embeddings of S in G (injective, label-preserving, every pattern edge a graph
    edge), the minimum over the pattern vertices of the number of distinct images;
  * W(S): the largest lightest-edge weight over all embeddings;
  * result set: the patterns with at least one edge, MNI(S) >= tau_s and W(S) >= tau_w.

W is also computed a second way, as the largest lightest-edge weight over the edge subsets of G with
the canonical form of S; the two must agree.

The graphs cover repeated vertex labels, symmetric patterns, weights equal to the threshold, weight 0,
cycles, a disconnected graph, one heavy occurrence among light ones, and patterns reachable from several
parents; seeded random graphs add the rest. Every (graph, tau_s, tau_w) cell runs the Java miner once
(BenchmarkMain, WeLT, DUMP_PATTERNS) and its result set is compared with the oracle's, with W.

Usage (from the repository root, after ./build.sh):
  bruteforce_oracle.py run [--classes target/classes] [--out <dir>] [--json <file>]
      Prints graphs, threshold pairs, patterns compared, false positives (returned by WeLT, not by
      the oracle), false negatives and W mismatches; exit 1 on any difference or when no cell ran.
  bruteforce_oracle.py --self-test
      Checks the oracle itself on hand-worked cases.
"""
import argparse
import itertools
import os
import random
import subprocess
import sys
import tempfile

# ----------------------------------------------------------------------------- graphs


def write_lg(path, labels, edges):
    """labels: {vertex: label}; edges: [(a, b, w)]."""
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("t # 0\n")
        for v in sorted(labels):
            f.write(f"v {v} {labels[v]}\n")
        for a, b, w in edges:
            f.write(f"e {a} {b} {w:g}\n")


def read_lg(path):
    labels, weight = {}, {}
    for line in open(path, encoding="utf-8"):
        p = line.split()
        if not p:
            continue
        if p[0] == "v":
            labels[int(p[1])] = int(p[2])
        elif p[0] == "e":
            a, b = int(p[1]), int(p[2])
            if a != b:
                weight.setdefault((min(a, b), max(a, b)), float(p[3]))
    return labels, weight


def hand_graphs():
    """(name, labels, edges): one graph per case of the brief."""
    g = []
    # repeated vertex labels: a path of one label
    g.append(("same_label_path", {i: 1 for i in range(5)}, [(0, 1, 3), (1, 2, 1), (2, 3, 3), (3, 4, 2)]))
    # symmetric patterns: a triangle and a star of one label
    g.append(("symmetric", {i: 1 for i in range(7)},
              [(0, 1, 2), (1, 2, 2), (0, 2, 2), (3, 4, 1), (3, 5, 2), (3, 6, 3)]))
    # weights equal to the threshold (the cells use every distinct weight as tau_w) and weight 0
    g.append(("zero_and_ties", {0: 1, 1: 2, 2: 1, 3: 2, 4: 1}, [(0, 1, 0), (1, 2, 2), (2, 3, 2), (3, 4, 0), (4, 1, 2)]))
    # cycles: a square with one chord and a pendant edge
    g.append(("cycles", {0: 1, 1: 2, 2: 1, 3: 2, 4: 3}, [(0, 1, 2), (1, 2, 3), (2, 3, 2), (3, 0, 3), (0, 2, 1), (2, 4, 2)]))
    # disconnected graph: two triangles with the same labels and a separate edge
    g.append(("disconnected", {0: 1, 1: 1, 2: 2, 3: 1, 4: 1, 5: 2, 6: 3, 7: 3},
              [(0, 1, 1), (1, 2, 1), (0, 2, 1), (3, 4, 3), (4, 5, 3), (3, 5, 2), (6, 7, 5)]))
    # one heavy occurrence among many light ones
    g.append(("heavy_among_light", {0: 1, 1: 2, 2: 2, 3: 2, 4: 2, 5: 1, 6: 2},
              [(0, 1, 1), (0, 2, 1), (0, 3, 1), (0, 4, 1), (5, 6, 9), (5, 4, 1)]))
    # a pattern reachable from several parents: a 4-cycle with alternating labels, each path a parent
    g.append(("many_parents", {0: 1, 1: 2, 2: 1, 3: 2, 4: 1, 5: 2, 6: 1, 7: 2},
              [(0, 1, 2), (1, 2, 2), (2, 3, 2), (3, 0, 2), (4, 5, 3), (5, 6, 3), (6, 7, 3), (7, 4, 3)]))
    # the same 4-cycle sharing vertices with a path, so the cycle's supports differ by vertex
    g.append(("shared_vertices", {0: 1, 1: 1, 2: 1, 3: 1, 4: 2, 5: 2},
              [(0, 1, 1), (1, 2, 2), (2, 3, 1), (3, 0, 2), (1, 4, 3), (2, 5, 3), (4, 5, 1)]))
    return g


def random_graphs(count, seed):
    rnd = random.Random(seed)
    out = []
    for k in range(count):
        n = rnd.randint(5, 8)
        nl = rnd.choice((1, 2, 3))
        labels = {v: rnd.randint(1, nl) for v in range(n)}
        pairs = [(a, b) for a in range(n) for b in range(a + 1, n)]
        m = rnd.randint(n - 1, min(11, len(pairs)))
        edges = [(a, b, rnd.choice((0, 1, 2, 3, 5))) for a, b in rnd.sample(pairs, m)]
        out.append((f"random_{seed}_{k}", labels, edges))
    return out


# ----------------------------------------------------------------------------- oracle


def canonical(vlabels, edges):
    """Canonical form of a labeled pattern (vertex labels; undirected edges between vertex indices):
    vertices are placed in label order, and the smallest sorted edge list over all such placements wins."""
    n = len(vlabels)
    order = sorted(range(n), key=lambda v: vlabels[v])
    groups = [list(g) for _k, g in itertools.groupby(order, key=lambda v: vlabels[v])]
    best = None
    for perms in itertools.product(*(itertools.permutations(g) for g in groups)):
        pos, i = {}, 0
        for perm in perms:
            for v in perm:
                pos[v] = i
                i += 1
        code = tuple(sorted((min(pos[a], pos[b]), max(pos[a], pos[b])) for a, b in edges))
        if best is None or code < best:
            best = code
    return (tuple(sorted(vlabels)), best)


def connected_edge_subsets(weight):
    """Every non-empty connected set of edges of G (edges as (a, b) with a < b)."""
    adj_e = {}
    for e in weight:
        for v in e:
            adj_e.setdefault(v, []).append(e)
    seen = set()
    stack = [frozenset([e]) for e in weight]
    while stack:
        s = stack.pop()
        if s in seen:
            continue
        seen.add(s)
        verts = {v for e in s for v in e}
        for v in verts:
            for e in adj_e[v]:
                if e not in s:
                    stack.append(s | {e})
    return seen


def pattern_of(edge_set, labels):
    verts = sorted({v for e in edge_set for v in e})
    idx = {v: i for i, v in enumerate(verts)}
    return [labels[v] for v in verts], [(idx[a], idx[b]) for a, b in edge_set]


def embeddings(vlabels, pedges, labels, adj):
    """Every injective label-preserving map of the pattern into G that maps pattern edges to graph edges."""
    n = len(vlabels)
    pnb = [set() for _ in range(n)]
    for a, b in pedges:
        pnb[a].add(b)
        pnb[b].add(a)
    m = [None] * n

    def rec(k):
        if k == n:
            yield tuple(m)
            return
        for g in labels:
            if labels[g] != vlabels[k] or g in m[:k]:
                continue
            if all(m[u] in adj[g] for u in pnb[k] if u < k):
                m[k] = g
                yield from rec(k + 1)
                m[k] = None

    yield from rec(0)


def oracle(labels, weight):
    """{canonical form: (MNI, W)} over every connected pattern of G."""
    adj = {v: set() for v in labels}
    for a, b in weight:
        adj[a].add(b)
        adj[b].add(a)
    w_subsets = {}
    for s in connected_edge_subsets(weight):
        vl, pe = pattern_of(s, labels)
        c = canonical(vl, pe)
        w_subsets[c] = max(w_subsets.get(c, float("-inf")), min(weight[e] for e in s))
    out = {}
    for c, w_sub in w_subsets.items():
        vl, pe = list(c[0]), list(c[1])
        images = [set() for _ in vl]
        w = float("-inf")
        for e in embeddings(vl, pe, labels, adj):
            for i, g in enumerate(e):
                images[i].add(g)
            w = max(w, min(weight[(min(e[a], e[b]), max(e[a], e[b]))] for a, b in pe))
        if w != w_sub:
            raise AssertionError(f"W of {c}: {w} over embeddings, {w_sub} over edge subsets")
        out[c] = (min(len(s) for s in images), w)
    return out


def result_set(facts, min_sup, min_weight):
    return {c: w for c, (mni, w) in facts.items() if mni >= min_sup and w >= min_weight}


def parse_code(code):
    """Canonical code of the miner ('i j li le lj;' per edge) -> canonical form of the oracle."""
    vl, pe = {}, []
    for t in code.strip().rstrip(";").split(";"):
        i, j, li, le, lj = (int(x) for x in t.split())
        if le != 0:
            raise ValueError(f"edge label {le} in {code!r}")
        vl[i], vl[j] = li, lj
        pe.append((i, j))
    return canonical([vl[v] for v in range(len(vl))], pe)


# ----------------------------------------------------------------------------- run


def run(args):
    out = args.out or tempfile.mkdtemp(prefix="bruteforce-")
    os.makedirs(out, exist_ok=True)
    graphs = hand_graphs() + random_graphs(args.random, args.seed)
    stats = {"graphs": 0, "threshold_pairs": 0, "patterns": 0, "fp": 0, "fn": 0, "w_mismatch": 0, "failed_runs": 0}
    shown = 0
    for name, labels, edges in graphs:
        path = os.path.join(out, name + ".lg")
        write_lg(path, labels, edges)
        lab, weight = read_lg(path)
        facts = oracle(lab, weight)
        stats["graphs"] += 1
        taus = sorted({w for w in weight.values()})
        for s in (1, 2, 3):
            for tw in taus + [max(taus) + 1]:
                dump = os.path.join(out, f"{name}_{s}_{tw:g}")
                os.makedirs(dump, exist_ok=True)
                env = dict(os.environ, BENCH_ALGOS="WeLT", DUMP_PATTERNS=dump)
                env.pop("BENCH_CSV", None)
                r = subprocess.run(["java", "-cp", args.classes, "welt.runner.BenchmarkMain", path, str(s), f"{tw:g}",
                                    "0", "60000", "0", "1"], env=env, capture_output=True, text=True)
                files = [f for f in os.listdir(dump) if f.endswith(".tsv")]
                if r.returncode != 0 or len(files) != 1:
                    stats["failed_runs"] += 1
                    print(f"RUN FAILED {name} tau_s={s} tau_w={tw:g}: exit {r.returncode}, {len(files)} dump files")
                    continue
                stats["threshold_pairs"] += 1
                got = {}
                with open(os.path.join(dump, files[0]), encoding="utf-8") as f:
                    next(f)
                    for line in f:
                        code, _v, _e, w = line.rstrip("\n").split("\t")
                        got[parse_code(code)] = float(w)
                want = result_set(facts, s, tw)
                fp, fn = set(got) - set(want), set(want) - set(got)
                wm = [c for c in set(got) & set(want) if got[c] != want[c]]
                stats["patterns"] += len(set(got) | set(want))
                stats["fp"] += len(fp)
                stats["fn"] += len(fn)
                stats["w_mismatch"] += len(wm)
                for kind, cs in (("false positive", fp), ("false negative", fn), ("W mismatch", wm)):
                    for c in sorted(cs):
                        if shown < 10:
                            print(f"{kind}: {name} tau_s={s} tau_w={tw:g} pattern {c} oracle {want.get(c)} miner {got.get(c)}")
                        shown += 1
    print("graphs={graphs} threshold_pairs={threshold_pairs} patterns={patterns} fp={fp} fn={fn} "
          "w_mismatch={w_mismatch} failed_runs={failed_runs}".format(**stats))
    ok = stats["threshold_pairs"] > 0 and stats["patterns"] > 0 and not any(
        stats[k] for k in ("fp", "fn", "w_mismatch", "failed_runs"))
    print("bruteforce_oracle: " + ("OK" if ok else "DIFFERENCES"))
    if args.json:
        import json
        with open(args.json, "w", encoding="utf-8", newline="\n") as f:
            json.dump(stats, f, indent=1, sort_keys=True)
            f.write("\n")
    return 0 if ok else 1


# ----------------------------------------------------------------------------- self-test


def self_test():
    fails = 0

    def check(name, got, want):
        nonlocal fails
        print(("ok   " if got == want else "FAIL ") + f"{name}: got {got}, want {want}")
        fails += got != want

    # canonical form: isomorphic patterns agree, a relabelled one does not
    check("path a-b-a, two vertex orders", canonical([1, 2, 1], [(0, 1), (1, 2)]) == canonical([2, 1, 1], [(0, 1), (0, 2)]), True)
    check("path a-b-a differs from b-a-b", canonical([1, 2, 1], [(0, 1), (1, 2)]) == canonical([2, 1, 2], [(0, 1), (1, 2)]), False)
    check("triangle differs from path", canonical([1, 1, 1], [(0, 1), (1, 2), (0, 2)]) == canonical([1, 1, 1], [(0, 1), (1, 2)]), False)
    # connected subsets of a triangle: 3 single edges, 3 paths, 1 triangle
    tri = {(0, 1): 1.0, (1, 2): 2.0, (0, 2): 3.0}
    check("connected edge subsets of a triangle", len(connected_edge_subsets(tri)), 7)
    # same-label triangle with weights 1, 2, 3: one-edge pattern MNI 3, W 3; path MNI 3, W 2; triangle MNI 3, W 1
    f = oracle({0: 1, 1: 1, 2: 1}, tri)
    by_edges = {len(c[1]): v for c, v in f.items()}
    check("triangle: patterns", len(f), 3)
    check("triangle: (MNI, W) by edge count", by_edges, {1: (3, 3.0), 2: (3, 2.0), 3: (3, 1.0)})
    check("triangle: tau_w equal to a weight keeps the pattern", len(result_set(f, 3, 2.0)), 2)
    # star centre 1 with leaves 2,2,2: edge MNI = min(1, 3) = 1
    f = oracle({0: 1, 1: 2, 2: 2, 3: 2}, {(0, 1): 1.0, (0, 2): 1.0, (0, 3): 5.0})
    edge = [v for c, v in f.items() if len(c[1]) == 1][0]
    check("star: one-edge MNI is the centre's single image, W the heavy edge", edge, (1, 5.0))
    check("miner code parsed to the same form", parse_code("0 1 1 0 2;0 2 1 0 2;"), canonical([1, 2, 2], [(0, 1), (0, 2)]))
    print(f"bruteforce_oracle self-test: {'OK' if fails == 0 else 'FAILED'} ({9 - fails}/9)")
    return 1 if fails else 0


def main():
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    ap = argparse.ArgumentParser()
    ap.add_argument("command", choices=["run"])
    ap.add_argument("--classes", default="target/classes")
    ap.add_argument("--out")
    ap.add_argument("--random", type=int, default=22)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--json", help="write the counts to this file")
    sys.exit(run(ap.parse_args()))


if __name__ == "__main__":
    main()
