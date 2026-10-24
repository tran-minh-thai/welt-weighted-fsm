#!/usr/bin/env python3
"""Validity check of a node-classification dataset for the downstream experiment: does the
structure-only baseline B0 (degree, total incident weight) beat chance?

    python tools/downstream/baseline_check.py <graph.lg> <labels.lg> <out.json> [--min-class N]

<graph.lg> is the mining graph (its edges give B0); <labels.lg> carries the class of every
vertex as its vertex label (same vertex ids, checked). Classes with fewer than --min-class
members (default: the number of outer folds) are dropped from the evaluation, because stratified
k-fold needs k members per class; the count of dropped classes and vertices is recorded.

Chance levels are exact expectations for the classes kept: a uniform random guess has expected
per-class F1 2 p_c q / (p_c + q) with q = 1/K, and a guess drawn from the class prior has expected
per-class F1 p_c, so its macro-F1 is 1/K. B0 is evaluated with the design of lib.evaluate
(same seeds, folds, model and inner choice of C). Writes <out.json> and prints a summary.
"""
import collections
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lib  # noqa: E402


def chance_levels(y):
    c = collections.Counter(y)
    n, k = len(y), len(c)
    q = 1.0 / k
    p = [v / n for v in c.values()]
    return {"classes": k, "uniform_macro_f1": sum(2 * x * q / (x + q) for x in p) / k,
            "prior_macro_f1": 1.0 / k, "uniform_accuracy": q, "prior_accuracy": sum(x * x for x in p)}


def main():
    args = sys.argv[1:]
    min_class = lib.FOLDS
    if "--min-class" in args:
        i = args.index("--min-class")
        min_class = int(args[i + 1])
        args = args[:i] + args[i + 2:]
    if len(args) != 3:
        sys.exit(__doc__)
    graph, labels_lg, out = args
    import numpy as np
    from scipy import sparse
    g_labels, edges = lib.read_lg(graph)
    y_all, _ = lib.read_lg(labels_lg)
    if len(y_all) != len(g_labels):
        sys.exit(f"ERROR: {graph} has {len(g_labels)} vertices, {labels_lg} has {len(y_all)}")
    counts = collections.Counter(y_all)
    keep_classes = {c for c, v in counts.items() if v >= min_class}
    keep = [v for v in range(len(y_all)) if y_all[v] in keep_classes]
    y = np.asarray([y_all[v] for v in keep])
    b0 = sparse.csr_matrix(lib.structural_columns(len(y_all), edges))[keep]
    rows = lib.evaluate({"B0": b0}, y, log=lambda m: None)
    f1 = [r["macro_f1"] for r in rows]
    acc = [r["accuracy"] for r in rows]
    res = {
        "graph": os.path.basename(graph), "graph_sha256": lib.sha256(graph),
        "labels": os.path.basename(labels_lg), "labels_sha256": lib.sha256(labels_lg),
        "min_class": min_class, "classes_total": len(counts), "classes_kept": len(keep_classes),
        "vertices_total": len(y_all), "vertices_kept": len(keep),
        "seeds": lib.SEEDS, "folds": lib.FOLDS, "fold_rows": len(rows),
        "B0_macro_f1_mean": float(np.mean(f1)), "B0_macro_f1_sd": float(np.std(f1, ddof=1)),
        "B0_accuracy_mean": float(np.mean(acc)), "B0_accuracy_sd": float(np.std(acc, ddof=1)),
        "B0_C_chosen": {str(c): sum(1 for r in rows if r["C"] == c) for c in lib.C_GRID},
        "chance": chance_levels(list(y)),
    }
    res["B0_folds_above_uniform_chance"] = sum(1 for x in f1 if x > res["chance"]["uniform_macro_f1"])
    res["B0_folds_above_prior_chance"] = sum(1 for x in f1 if x > res["chance"]["prior_macro_f1"])
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(res, f, indent=2)
    print(json.dumps(res, indent=2))


if __name__ == "__main__":
    main()
