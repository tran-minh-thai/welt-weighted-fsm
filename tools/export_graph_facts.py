#!/usr/bin/env python3
"""Facts of the input graph files that the reader resolves: pairs listed twice, conflicting weights, loops.

    python3 tools/export_graph_facts.py <out dir> [--tau-w <campaign numbers.json>] [--self-test]

Reads datasets/<name>.lg for the six graphs of the experiments, counts per graph the edge lines, the
self-loops, the vertex pairs listed more than once (in either direction) and, among them, those whose
lines carry different weights (the reader keeps the first; see LgGraphReader), and, over the edges
the reader keeps, the largest weight and the share of edges that carry it; writes <out dir>/numbers.json
with keys data.<ds>.{edge_lines,self_loops,bidir_pairs,bidir_pairs_diff_weight,max_weight,max_weight_edge_share}.
With --tau-w, reads each dataset's weight threshold data.<ds>.tau_w from a campaign numbers file and adds,
over the same edges, data.<ds>.rho_w = (tau_w - min) / (max - min), data.<ds>.tau_w_percentile = 100 x the
share of edges below tau_w, and data.<ds>.tau_w_kept_share = the share of edges of weight >= tau_w.
--self-test changes the weight of one repeated line, adds a self-loop, and gives one more edge the largest
weight, in a copy of a graph, and requires the three counts to change by one.
"""
import json
import shutil
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRAPHS = ("citeseer", "mico", "email_eu", "lastfm", "github", "string_ecoli")


def facts(path):
    first, repeated, diff, loops, lines = {}, set(), set(), 0, 0
    for line in open(path, encoding="utf-8"):
        t = line.split()
        if not t or t[0] != "e":
            continue
        lines += 1
        u, v, w = int(t[1]), int(t[2]), float(t[3])
        if u == v:
            loops += 1
            continue
        k = (min(u, v), max(u, v))
        if k in first:
            repeated.add(k)
            if first[k] != w:
                diff.add(k)
        else:
            first[k] = w
    top = max(first.values())
    return {"edge_lines": lines, "self_loops": loops, "bidir_pairs": len(repeated), "bidir_pairs_diff_weight": len(diff),
            "max_weight": top, "max_weight_edge_share": sum(1 for w in first.values() if w == top) / len(first),
            "_weights": list(first.values())}


def threshold_facts(weights, tw):
    lo, hi = min(weights), max(weights)
    return {"rho_w": (tw - lo) / (hi - lo), "tau_w_percentile": 100.0 * sum(1 for w in weights if w < tw) / len(weights),
            "tau_w_kept_share": sum(1 for w in weights if w >= tw) / len(weights)}


def main():
    out = Path(sys.argv[1])
    taus = None
    if "--tau-w" in sys.argv:
        src = json.load(open(sys.argv[sys.argv.index("--tau-w") + 1], encoding="utf-8"))
        vals = src.get("values", src)
        taus = {g: vals.get(f"data.{g}.tau_w") for g in GRAPHS}
        missing = [g for g, t in taus.items() if t is None]
        if missing:
            sys.exit(f"export_graph_facts: no data.<ds>.tau_w for {missing} in the campaign numbers")
    keys = {}
    for g in GRAPHS:
        p = ROOT / "datasets" / f"{g}.lg"
        if not p.exists():
            sys.exit(f"export_graph_facts: {p} is missing; run tools/fetch_datasets.py")
        f = facts(p)
        weights = f.pop("_weights")
        for k, v in f.items():
            keys[f"data.{g}.{k}"] = v
        if taus:
            for k, v in threshold_facts(weights, taus[g]).items():
                keys[f"data.{g}.{k}"] = v
    out.mkdir(parents=True, exist_ok=True)
    (out / "numbers.json").write_text(json.dumps(keys, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    for k in sorted(keys):
        print(k, keys[k])
    if "--self-test" in sys.argv:
        base = facts(ROOT / "datasets" / "citeseer.lg")
        with tempfile.TemporaryDirectory() as t:
            p = Path(t) / "g.lg"
            shutil.copy(ROOT / "datasets" / "citeseer.lg", p)
            lines = p.read_text(encoding="utf-8").splitlines(keepends=True)
            seen, idx = {}, None
            for i, l in enumerate(lines):
                tt = l.split()
                if tt and tt[0] == "e":
                    k = tuple(sorted((int(tt[1]), int(tt[2]))))
                    if k in seen:
                        idx = i
                        break
                    seen[k] = i
            tt = lines[idx].split()
            lines[idx] = f"e {tt[1]} {tt[2]} {float(tt[3]) + 1}\n"
            lines.append("e 0 0 1\n")
            for i, l in enumerate(lines):          # one edge below the largest weight gets the largest weight
                tt = l.split()
                if tt and tt[0] == "e" and tt[1] != tt[2] and float(tt[3]) < base["max_weight"] and i != idx:
                    lines[i] = f"e {tt[1]} {tt[2]} {base['max_weight']}\n"
                    break
            p.write_text("".join(lines), encoding="utf-8")
            got = facts(p)
        n = round(base["max_weight_edge_share"] * (base["edge_lines"] - base["self_loops"] - base["bidir_pairs"]))
        top_ok = round(got["max_weight_edge_share"] * (got["edge_lines"] - got["self_loops"] - got["bidir_pairs"])) == n + 1
        # threshold facts at the boundaries: tau_w at the smallest weight keeps every edge, above the largest
        # keeps none, between two weights splits them exactly
        w = [1.0, 2.0, 2.0, 5.0]
        t_ok = (threshold_facts(w, 1.0) == {"rho_w": 0.0, "tau_w_percentile": 0.0, "tau_w_kept_share": 1.0}
                and threshold_facts(w, 5.0)["tau_w_kept_share"] == 0.25 and threshold_facts(w, 5.0)["rho_w"] == 1.0
                and threshold_facts(w, 6.0)["tau_w_kept_share"] == 0.0
                and threshold_facts(w, 2.0) == {"rho_w": 0.25, "tau_w_percentile": 25.0, "tau_w_kept_share": 0.75})
        print(f"self-test: threshold facts at the boundaries {'passed' if t_ok else 'FAILED'}")
        ok = (got["bidir_pairs_diff_weight"] == base["bidir_pairs_diff_weight"] + 1 and got["self_loops"] == base["self_loops"] + 1
              and top_ok and t_ok)
        print(f"self-test: {'passed' if ok else 'FAILED'} (diff-weight pairs {base['bidir_pairs_diff_weight']} -> "
              f"{got['bidir_pairs_diff_weight']}, loops {base['self_loops']} -> {got['self_loops']}, "
              f"edges at the largest weight {n} -> {n + 1 if top_ok else 'unchanged'})")
        sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
