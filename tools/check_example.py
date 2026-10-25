#!/usr/bin/env python3
"""Check every number of the running example (datasets/example.lg) against the definitions and the miner.

    python3 tools/check_example.py [--repo DIR] [--out DIR] [--self-test]

Three independent sources are compared:

  definitions  occurrences, MNI support, bottleneck weight W, the ALL score and the two-edge
               lookup table, computed by brute force over every connected edge subset of the
               graph and every injective, label-preserving map (no code of the miner is used);
  search       the level-wise search of the miner re-implemented from its description: single
               edges with frequent end labels, the filters P1 and P2 of the two-edge table (with
               and without the bound on single edges), the closure filter P3, and extension of
               the patterns that pass the weight test (WeLT) or of every frequent pattern (GraMi);
  miner        welt.runner.BenchmarkMain compiled from DIR/src/main/java (default: this
               repository) and run on the example, with the pattern dump; and tools/example/
               ExampleTrace.java, which calls the start and order methods of the weight check.

Every comparison is printed with its two values; the summary gives the number of comparisons.
Exits 1 on any mismatch. With --self-test, four faults are injected (a changed edge weight, a
changed expected value, a pattern dropped from a dump, the single-edge bound switched) and each
must be caught; the clean run must pass.
"""
import csv
import hashlib
import io
import itertools
import json
import os
import platform
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

TOOL = Path(__file__).resolve()
REPO = TOOL.parent.parent
LETTER = {0: "A", 1: "B", 2: "C"}

# Expected values of the example (thresholds tau_s = 2, tau_w = 5 unless stated).
EXPECTED = {
    "occ.ABC": {"a1b1c1": 7.0, "a1b1c2": 3.0, "a1b2c2": 4.0, "a2b2c2": 5.0},
    "mni.ABC": 2, "W.ABC": 7.0,
    "occ.AC": {"a1c1": 2.0, "a2c2": 1.0},
    "mni.AC": 2, "W.AC": 2.0,
    "frequent.count": 7,
    "fws.2_5": {"A-B": 7.0, "B-C": 8.0, "A-B-C": 7.0},
    "fws.2_7": {"A-B": 7.0, "B-C": 8.0, "A-B-C": 7.0},
    "heavy.5": ["a1b1", "a2b2", "b1c1", "b2c2"],
    "all.4.5": ["A-B", "A-B-C", "B-C"],
    "heavy.6.5": ["a1b1", "b1c1"],
    "all.6.5": [],
    "table": {"A-B-C": [2, 7.0], "B-A-C": [2, 2.0], "A-C-B": [2, 2.0], "B-C-B": [1, None]},
    "p1.ABCB.missing": ["B-C-B"],
    "p2.triangle.ub2": 2.0,
    # counters of the miner at (2, 5): N generated, M reaching the MNI count, M' frequent
    "WeLT.N": 17, "WeLT.M": 3, "WeLT.Mprime": 3, "WeLT.R": 3,
    "WeLT.M.no_single_edge_bound": 4,
    "GraMi.N": 34, "GraMi.M": 16, "GraMi.Mprime": 7, "GraMi.R": 7,
    "trace.start_label": "B", "trace.order": "B,A,C",
}


# ----------------------------------------------------------------------------- definitions

def read_lg(path):
    labels, edges = {}, {}
    for line in open(path, encoding="utf-8"):
        t = line.split()
        if not t or t[0] == "#" or t[0] == "t":
            continue
        if t[0] == "v":
            labels[int(t[1])] = int(t[2])
        elif t[0] == "e":
            u, v = sorted((int(t[1]), int(t[2])))
            edges[(u, v)] = float(t[3])
    return labels, edges


def names(labels):
    out = {}
    for lab in sorted(set(labels.values())):
        for i, v in enumerate(sorted(x for x in labels if labels[x] == lab), 1):
            out[v] = LETTER[lab].lower() + str(i)
    return out


def canon(plabels, pedges):
    """Canonical form of a small pattern: the smallest relabelled (labels, edges) over all permutations."""
    n = len(plabels)
    best = None
    for perm in itertools.permutations(range(n)):
        lab = tuple(plabels[perm.index(i)] for i in range(n))
        ed = tuple(sorted(tuple(sorted((perm[a], perm[b]))) for a, b in pedges))
        key = (lab, ed)
        if best is None or key < best:
            best = key
    return best


def occurrences(plabels, pedges, labels, edges):
    """Every injective map of the pattern into G that keeps vertex labels and maps edges to edges."""
    n = len(plabels)
    for img in itertools.permutations(sorted(labels), n):
        if any(labels[img[i]] != plabels[i] for i in range(n)):
            continue
        ws = []
        for a, b in pedges:
            k = tuple(sorted((img[a], img[b])))
            if k not in edges:
                break
            ws.append(edges[k])
        else:
            yield img, ws


def measures(plabels, pedges, labels, edges, alpha=None):
    images = [set() for _ in plabels]
    heavy = [set() for _ in plabels]
    w = 0.0
    occ = []
    for img, ws in occurrences(plabels, pedges, labels, edges):
        occ.append((img, ws))
        for i, v in enumerate(img):
            images[i].add(v)
        w = max(w, min(ws))
        if alpha is not None and min(ws) > alpha:
            for i, v in enumerate(img):
                heavy[i].add(v)
    mni = min(len(s) for s in images) if occ else 0
    all_score = (min(len(s) for s in heavy) if occ else 0) if alpha is not None else None
    return mni, w, all_score, occ


def all_patterns(labels, edges):
    """Every pattern with an occurrence: the connected edge subsets of G, up to isomorphism."""
    elist = sorted(edges)
    seen = {}
    for r in range(1, len(elist) + 1):
        for sub in itertools.combinations(elist, r):
            verts = sorted({x for e in sub for x in e})
            idx = {v: i for i, v in enumerate(verts)}
            pl = [labels[v] for v in verts]
            pe = [(idx[a], idx[b]) for a, b in sub]
            if not connected(len(pl), pe):
                continue
            c = canon(pl, pe)
            seen.setdefault(c, (pl, pe))
    return seen


def connected(n, pe):
    adj = {i: set() for i in range(n)}
    for a, b in pe:
        adj[a].add(b)
        adj[b].add(a)
    stack, seen = [0], {0}
    while stack:
        for w in adj[stack.pop()]:
            if w not in seen:
                seen.add(w)
                stack.append(w)
    return len(seen) == n


def path_name(c):
    """Name of a pattern in canonical form: an edge X-Y, a path X-Y-Z (centre Y), or a list of edges."""
    lab, ed = c
    if len(ed) == 1:
        return "-".join(sorted(LETTER[l] for l in lab))
    deg = [sum(i in e for e in ed) for i in range(len(lab))]
    if len(ed) == 2 and len(lab) == 3:
        centre = deg.index(2)
        ends = sorted(LETTER[lab[i]] for i in range(3) if i != centre)
        return f"{ends[0]}-{LETTER[lab[centre]]}-{ends[1]}"
    if len(lab) == 3 and len(ed) == 3:
        return "triangle " + "".join(sorted(LETTER[l] for l in lab))
    return ";".join(f"{LETTER[lab[a]]}{a}-{LETTER[lab[b]]}{b}" for a, b in ed)


def sub_paths(pl, pe):
    """Connected two-edge subpatterns of a pattern, as canonical forms (pairs of edges sharing a vertex)."""
    out = []
    for e1, e2 in itertools.combinations(pe, 2):
        shared = set(e1) & set(e2)
        if len(shared) != 1:
            continue
        b = shared.pop()
        a = e1[0] if e1[1] == b else e1[1]
        c = e2[0] if e2[1] == b else e2[1]
        out.append(canon([pl[a], pl[b], pl[c]], [(0, 1), (1, 2)]))
    return out


def deletions(pl, pe):
    """Connected patterns obtained by deleting one edge (and a vertex left isolated)."""
    out = []
    for i in range(len(pe)):
        rest = pe[:i] + pe[i + 1:]
        used = sorted({x for e in rest for x in e})
        if not rest:
            continue
        idx = {v: j for j, v in enumerate(used)}
        if len(used) < len(pl) - 1:
            continue
        sl = [pl[v] for v in used]
        se = [(idx[a], idx[b]) for a, b in rest]
        if connected(len(sl), se):
            out.append(canon(sl, se))
    return out


def search(labels, edges, tau_s, tau_w, algo, single_edge_bound=True):
    """Level-wise search of the miner, re-implemented from its description; returns its counters."""
    lab_count = {}
    for v in labels.values():
        lab_count[v] = lab_count.get(v, 0) + 1
    freq_labels = sorted(l for l, c in lab_count.items() if c >= tau_s)
    triples = {tuple(sorted((labels[u], labels[v]))) for u, v in edges}
    type_max = {}
    for (u, v), w in edges.items():
        k = tuple(sorted((labels[u], labels[v])))
        type_max[k] = max(type_max.get(k, 0.0), w)
    memo = {}

    def meas(pl, pe):
        c = canon(pl, pe)
        if c not in memo:
            memo[c] = measures(pl, pe, labels, edges)[:2]
        return memo[c]

    table = {}  # frequent two-edge paths -> W
    for lo, hi in triples:
        if lo in freq_labels and hi in freq_labels:
            for u in (0, 1):
                for lb in freq_labels:
                    pl = [lo, hi]
                    if tuple(sorted((pl[u], lb))) not in triples:
                        continue
                    pl2, pe2 = pl + [lb], [(0, 1), (u, 2)]
                    mni, w = meas(pl2, pe2)
                    if mni >= tau_s:
                        table[canon(pl2, pe2)] = w
    level = []
    for lo, hi in sorted(triples):
        if lo in freq_labels and hi in freq_labels:
            level.append(([lo, hi], [(0, 1)]))
    visited, confirmed = set(), set()
    n_hat = n = m = m_prime = 0
    result = {}
    while level:
        nxt = []
        for pl, pe in level:
            n_hat += 1
            c = canon(pl, pe)
            if c in visited:
                continue
            visited.add(c)
            n += 1
            if algo == "WeLT":
                if len(pe) == 1:
                    if single_edge_bound and type_max[tuple(sorted(pl))] < tau_w:
                        continue
                else:
                    subs = sub_paths(pl, pe)
                    if any(s not in table for s in subs):          # P1
                        continue
                    if min(table[s] for s in subs) < tau_w:          # P2
                        continue
            if len(pe) >= 2 and any(d not in confirmed for d in deletions(pl, pe)):  # P3
                continue
            m += 1
            mni, w = meas(pl, pe)
            if mni < tau_s:
                continue
            confirmed.add(c)
            m_prime += 1
            fws = w >= tau_w
            if algo == "GraMi" or fws:
                result[c] = w
            if algo == "GraMi" or fws:
                k = len(pl)
                for u in range(k):
                    for lb in freq_labels:
                        if tuple(sorted((pl[u], lb))) in triples:
                            nxt.append((pl + [lb], pe + [(u, k)]))
                for u, v in itertools.combinations(range(k), 2):
                    if (u, v) not in pe and (v, u) not in pe and tuple(sorted((pl[u], pl[v]))) in triples:
                        nxt.append((pl, pe + [(u, v)]))
        level = nxt
    return {"N_hat": n_hat, "N": n, "M": m, "Mprime": m_prime, "R": len(result), "result": result,
            "table": table}


# ----------------------------------------------------------------------------- miner

def run_miner(repo, graph, work):
    cls = work / "classes"
    cls.mkdir()
    sources = [str(p) for p in sorted((repo / "src/main/java").rglob("*.java"))]
    sources.append(str(TOOL.parent / "example" / "ExampleTrace.java"))
    subprocess.run(["javac", "-nowarn", "-d", str(cls)] + sources, check=True, capture_output=True)
    dump = work / "dump"
    runs = {}
    for algo in ("GraMi", "GraMiFilter", "WeLT"):
        for tw in ("5", "7"):
            env = dict(os.environ, BENCH_CSV="1", BENCH_ALGOS=algo, DUMP_PATTERNS=str(dump))
            for k in ("WELT_VOTER", "WELT_WORDER", "WELT_TABLE_REUSE", "WELT_TABLE_THRESHOLD"):
                env.pop(k, None)
            out = subprocess.run(["java", "-cp", str(cls), "welt.runner.BenchmarkMain", str(graph), "2", tw,
                                  "0", "0", "1", "1"], env=env, capture_output=True, text=True, check=True).stdout
            rows = [l[4:] for l in out.splitlines(keepends=True) if l.startswith("CSV,")]
            row = next(csv.DictReader(io.StringIO("".join(rows))))
            pats = {}
            for line in list(open(dump / f"{graph.stem}_{algo}_2_{float(tw)}.tsv", encoding="utf-8"))[1:]:
                code, _, _, w = line.rstrip("\n").split("\t")
                pats[code_to_canon(code)] = float(w)
            runs[(algo, tw)] = {"row": row, "patterns": pats}
    trace = subprocess.run(["java", "-cp", str(cls), "ExampleTrace", str(graph), "5", "0", "1", "2"],
                           capture_output=True, text=True, check=True).stdout
    jv = subprocess.run(["java", "-version"], capture_output=True, text=True).stderr.splitlines()[0]
    return runs, dict(l.split("=", 1) for l in trace.splitlines()), jv


def code_to_canon(code):
    """Parse a DFS code 'i j li el lj;...' of the miner into the canonical form used here."""
    lab, pe = {}, []
    for part in code.strip(";").split(";"):
        i, j, li, _, lj = (int(x) for x in part.split())
        lab[i], lab[j] = li, lj
        pe.append((i, j))
    return canon([lab[i] for i in range(len(lab))], pe)


# ----------------------------------------------------------------------------- comparisons

class Report:
    def __init__(self):
        self.lines, self.bad, self.n = [], [], 0

    def eq(self, what, expected, got):
        self.n += 1
        ok = expected == got
        self.lines.append(f"{'ok      ' if ok else 'MISMATCH'} {what}: expected {expected!r}, got {got!r}")
        if not ok:
            self.bad.append(what)


def check(graph, expected, runs, trace, flip_bound=False):
    rep = Report()
    labels, edges = read_lg(graph)
    nm = names(labels)
    inv = {v: k for k, v in nm.items()}
    A, B, C = 0, 1, 2
    pats = all_patterns(labels, edges)

    def occ_map(pl, pe):
        mni, w, _, occ = measures(pl, pe, labels, edges)
        return mni, w, {"".join(nm[v] for v in img): min(ws) for img, ws in occ}

    mni, w, occ = occ_map([A, B, C], [(0, 1), (1, 2)])
    rep.eq("occurrences of A-B-C and their bottlenecks", expected["occ.ABC"], occ)
    rep.eq("MNI of A-B-C", expected["mni.ABC"], mni)
    rep.eq("W of A-B-C", expected["W.ABC"], w)
    mni, w, occ = occ_map([A, C], [(0, 1)])
    rep.eq("occurrences of A-C", expected["occ.AC"], occ)
    rep.eq("MNI of A-C", expected["mni.AC"], mni)
    rep.eq("W of A-C", expected["W.AC"], w)

    meas = {c: measures(pl, pe, labels, edges) for c, (pl, pe) in pats.items()}
    freq = {c for c, x in meas.items() if x[0] >= 2}
    rep.eq("number of frequent patterns at tau_s=2", expected["frequent.count"], len(freq))
    for tw in (5, 7):
        fws = {path_name(c): meas[c][1] for c in freq if meas[c][1] >= tw}
        rep.eq(f"FWS at (2,{tw}) with W", expected[f"fws.2_{tw}"], fws)
    for alpha, tw in ((4.5, 5), (6.5, 7)):
        heavy = sorted("".join(sorted((nm[u], nm[v]))) for (u, v), x in edges.items() if x > alpha)
        rep.eq(f"edges heavier than alpha={alpha}", expected["heavy.5" if alpha == 4.5 else "heavy.6.5"], heavy)
        all_set = sorted(path_name(c) for c, (pl, pe) in pats.items()
                         if measures(pl, pe, labels, edges, alpha)[2] >= 2)
        rep.eq(f"ALL score at alpha={alpha}, tau_s=2", expected[f"all.{alpha}"], all_set)
        if not any(alpha < x < tw for x in edges.values()):
            fws = {c for c in freq if meas[c][1] >= tw}
            inside = {c for c, (pl, pe) in pats.items() if measures(pl, pe, labels, edges, alpha)[2] >= 2} <= fws
            rep.eq(f"ALL set inside the FWS set at alpha={alpha}, tau_w={tw}", True, inside)

    table = {}
    for c, (pl, pe) in pats.items():
        if len(pe) == 2:
            table[path_name(c)] = [meas[c][0], meas[c][1] if meas[c][0] >= 2 else None]
    rep.eq("lookup-table rows listed in the example",
           expected["table"], {k: table.get(k) for k in expected["table"]})
    rep.eq("frequent two-edge paths (all entries of the table)", sorted(k for k, v in expected["table"].items()
           if v[1] is not None), sorted(k for k, v in table.items() if v[0] >= 2))
    f2 = {canon(*pats[c]) for c in pats if len(pats[c][1]) == 2 and meas[c][0] >= 2}
    missing = sorted(path_name(s) for s in sub_paths([A, B, C, B], [(0, 1), (1, 2), (2, 3)]) if s not in f2)
    rep.eq("two-edge subpatterns of A-B-C-B missing from the table (P1)", expected["p1.ABCB.missing"], missing)
    tri = sub_paths([A, B, C], [(0, 1), (1, 2), (0, 2)])
    ub2 = min(meas[s][1] for s in tri)
    rep.eq("UB_2 of the triangle (P2)", expected["p2.triangle.ub2"], ub2)

    welt = search(labels, edges, 2, 5, "WeLT", single_edge_bound=not flip_bound)
    welt_nb = search(labels, edges, 2, 5, "WeLT", single_edge_bound=flip_bound)
    grami = search(labels, edges, 2, 5, "GraMi")
    for k in ("N", "M", "Mprime", "R"):
        rep.eq(f"search, WeLT {k}", expected[f"WeLT.{k}"], welt[k])
        rep.eq(f"search, GraMi {k}", expected[f"GraMi.{k}"], grami[k])
    rep.eq("search, WeLT M without the single-edge bound", expected["WeLT.M.no_single_edge_bound"], welt_nb["M"])
    fws5 = {c: meas[c][1] for c in freq if meas[c][1] >= 5}
    rep.eq("search, WeLT result equals the FWS set", fws5, welt["result"])
    rep.eq("search, GraMi result equals the frequent set", freq, set(grami["result"]))
    rep.eq("search, table entries equal the frequent two-edge paths", f2, set(welt["table"]))

    if runs is not None:
        for tw in ("5", "7"):
            fws = {c: meas[c][1] for c in freq if meas[c][1] >= int(tw)}
            rep.eq(f"miner, WeLT result and W at (2,{tw})", fws, runs[("WeLT", tw)]["patterns"])
            rep.eq(f"miner, GraMiFilter result and W at (2,{tw})", fws, runs[("GraMiFilter", tw)]["patterns"])
            rep.eq(f"miner, GraMi result at (2,{tw})", freq, set(runs[("GraMi", tw)]["patterns"]))
        for algo, ref in (("WeLT", welt), ("GraMi", grami)):
            row = runs[(algo, "5")]["row"]
            for k in ("N", "M", "Mprime", "R"):
                rep.eq(f"miner, {algo} {k} at (2,5) against the search", ref[k], int(row[k]))
        row = runs[("WeLT", "5")]["row"]
        rep.eq("miner, WeLT table size against the frequent two-edge paths", len(f2), int(row["tableSize"]))
        rep.eq("miner, WeLT settings (voter, weight order, reuse, threshold table)",
               ["1;1;1", "on", "on", "on"], [row["voter"], row["worder"], row["tableReuse"], row["tableThreshold"]])
        rep.eq("trace, start vertex of the weight check of A-B-C", expected["trace.start_label"],
               LETTER[int(trace["start_label"])])
        rep.eq("trace, matching order", expected["trace.order"],
               ",".join(LETTER[int(x)] for x in trace["order_labels"].split(",")))
        rep.eq("trace, weight check answer with and without branch-and-bound", ["true", "true"],
               [trace["embeds_bnb"].split()[0], trace["embeds_nobnb"].split()[0]])
    return rep, welt, grami, nm


def meta(repo, graph, jv):
    src = subprocess.run(["git", "-C", str(repo), "rev-parse", "HEAD:src"], capture_output=True, text=True).stdout.strip()
    commit = subprocess.run(["git", "-C", str(repo), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
    dirty = subprocess.run(["git", "-C", str(repo), "status", "--porcelain", "--", "src"],
                           capture_output=True, text=True).stdout.strip()
    home = str(Path.home())
    return {"command": " ".join(["python3", "tools/check_example.py"] + sys.argv[1:]).replace(home, "~"),
            "tool_sha256": hashlib.sha256(TOOL.read_bytes()).hexdigest(),
            "trace_sha256": hashlib.sha256((TOOL.parent / "example" / "ExampleTrace.java").read_bytes()).hexdigest(),
            "graph": graph.name, "graph_sha256": hashlib.sha256(graph.read_bytes()).hexdigest(),
            "miner_commit": commit, "miner_src_tree": src, "miner_src_clean": not dirty,
            "java": jv, "python": platform.python_version(), "machine": platform.node()}


def main():
    args = sys.argv[1:]
    repo = Path(args[args.index("--repo") + 1]).resolve() if "--repo" in args else REPO
    out = Path(args[args.index("--out") + 1]) if "--out" in args else None
    graph = repo / "datasets" / "example.lg"
    work = Path(tempfile.mkdtemp(prefix="welt-example-"))
    try:
        runs, trace, jv = run_miner(repo, graph, work)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    rep, welt, grami, nm = check(graph, EXPECTED, runs, trace)
    text = rep.lines + [f"WeLT search: N_hat {welt['N_hat']}, N {welt['N']}; GraMi search: N_hat {grami['N_hat']}, "
                        f"N {grami['N']}",
                        "miner counters at (2,5): " + "; ".join(
                            f"{a} " + ", ".join(f"{k} {runs[(a, '5')]['row'][k]}" for k in
                                                ("N", "M", "Mprime", "R", "prunedP1", "prunedP2", "prunedP2Single",
                                                 "prunedP3", "supportReused", "weightReused", "mniIsoCallsMine",
                                                 "weightIsoCalls", "tableBuildCandidates", "tableBuildMniIsoCalls",
                                                 "tableBuildWeightIsoCalls"))
                            for a in ("WeLT", "GraMi")),
                        "trace: " + "; ".join(f"{k} {v}" for k, v in trace.items()),
                        f"check_example: {rep.n} comparisons, {len(rep.bad)} mismatches"]
    selftest_ok = True
    if "--self-test" in args:
        text.append("self-test (each fault must be caught):")
        caught = 0
        faults = []
        tmp = Path(tempfile.mkdtemp(prefix="welt-example-fault-"))
        try:
            g2 = tmp / "example.lg"
            g2.write_text(graph.read_text(encoding="utf-8").replace("e 2 4 8", "e 2 4 4"), encoding="utf-8")
            faults.append(("edge weight b1c1 8 -> 4", lambda: check(g2, EXPECTED, runs, trace)[0]))
            e2 = json.loads(json.dumps(EXPECTED))
            e2["frequent.count"] = 6
            faults.append(("expected number of frequent patterns 7 -> 6", lambda: check(graph, e2, runs, trace)[0]))
            r2 = {k: {"row": v["row"], "patterns": dict(v["patterns"])} for k, v in runs.items()}
            r2[("WeLT", "5")]["patterns"].popitem()
            faults.append(("one pattern dropped from the WeLT dump", lambda: check(graph, EXPECTED, r2, trace)[0]))
            faults.append(("single-edge bound switched in the search", lambda: check(graph, EXPECTED, runs, trace,
                                                                                     flip_bound=True)[0]))
            for name, f in faults:
                r = f()
                ok = bool(r.bad)
                caught += ok
                text.append(f"  {'caught' if ok else 'MISSED'}: {name} ({len(r.bad)} of {r.n} comparisons fail)")
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
        text.append(f"self-test: {caught}/{len(faults)} faults caught; clean run "
                    f"{'passes' if not rep.bad else 'fails'}")
        selftest_ok = caught == len(faults) and not rep.bad
    print("\n".join(text))
    if out is not None:
        out.mkdir(parents=True, exist_ok=True)
        (out / "check_example.txt").write_text("\n".join(text) + "\n", encoding="utf-8")
        (out / "run_meta.json").write_text(json.dumps(meta(repo, graph, jv), indent=2) + "\n", encoding="utf-8")
    sys.exit(1 if rep.bad or not selftest_ok else 0)


if __name__ == "__main__":
    main()
