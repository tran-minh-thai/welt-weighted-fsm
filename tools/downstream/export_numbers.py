#!/usr/bin/env python3
"""Export the numbers of the downstream experiment as one JSON data file (no formatting, no labels).

    python tools/downstream/export_numbers.py <campaign dir> <baseline-check dir> <out.json> [<github probe choice.json>]

<campaign dir> holds B8_downstream_lastfm (the reported run) and B8_downstream_github (its scan
outcome); <baseline-check dir> holds lastfm.json, email_eu.json and github.json written by
baseline_check.py; the optional <github probe choice.json> is the downstream_choice.json of a
GitHub probe scan, from which only pattern counts are read (they do not depend on the machine): the
largest FWS count over the cells of the larger rho_w, all completed. Every value is read from these
artifacts; nothing is typed in. Each key records
its source file (path relative to the repository and SHA-256), so a number in the paper can be
traced back. Exits 1, naming the failed condition, when an artifact is not the one the paper may
cite: a run that is not a completed measurement on a clean tree with the design settings, a
missing fold, a configuration or test missing, a baseline check that does not reproduce the B0
of the reported run exactly, or a check run on a modified tree.
"""
import csv
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)
import lib  # noqa: E402

NAMES = {"B0": "B0", "B1": "B1", "FWS": "fws", "Frequent (same count)": "freq_same", "Frequent (all)": "freq_all",
         "ALL (images in G)": "all_g", "ALL (images in heavy edges)": "all_heavy"}


class Out:
    def __init__(self):
        self.values, self.sources, self.problems = {}, {}, []

    def put(self, key, value, path):
        if key in self.values:
            raise ValueError(f"key {key} written twice")
        self.values[key] = value
        self.sources[key] = path

    def need(self, ok, msg):
        if not ok:
            self.problems.append(msg)


def config_key(name):
    """B0 -> B0; "B1 + Frequent (all)" -> B1_freq_all."""
    if " + " not in name:
        return NAMES[name]
    base, rest = name.split(" + ", 1)
    return f"{base}_{NAMES[rest]}"


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def main():
    if len(sys.argv) not in (4, 5):
        sys.exit(__doc__)
    camp, checks, out_path = sys.argv[1:4]
    probe = sys.argv[4] if len(sys.argv) == 5 else None
    o = Out()
    files = {}

    def src(path):
        """The path relative to the root of the git checkout that holds it (the campaign results may
        be in another checkout than this tool), and its SHA-256."""
        top = os.path.dirname(os.path.abspath(path))
        while not os.path.exists(os.path.join(top, ".git")) and os.path.dirname(top) != top:
            top = os.path.dirname(top)
        r = os.path.relpath(os.path.abspath(path), top).replace(os.sep, "/")
        files[r] = lib.sha256(path)
        return r

    # ---- the reported run (LastFM)
    d = os.path.join(camp, "B8_downstream_lastfm")
    meta_p, sum_p, cho_p, fold_p = (os.path.join(d, f) for f in
                                    ("run_meta.json", "summary.json", "downstream_choice.json", "folds.csv"))
    meta, summ, cho = load(meta_p), load(sum_p), load(cho_p)
    o.need(meta.get("outcome") == "completed", f"{meta_p}: outcome {meta.get('outcome')!r}")
    o.need(meta.get("kind") == "measurement", f"{meta_p}: kind {meta.get('kind')!r}")
    o.need(meta.get("tree") == "clean", f"{meta_p}: tree {meta.get('tree')!r}")
    o.need(meta.get("departures_from_design") == {}, f"{meta_p}: departures {meta.get('departures_from_design')}")
    o.need(meta.get("design", {}).get("continuous_columns") == "log1p, then standardised",
           f"{meta_p}: continuous columns {meta.get('design', {}).get('continuous_columns')!r}")
    with open(fold_p, newline="") as f:
        folds = list(csv.DictReader(f))
    names = lib.config_names()
    expected_rows = len(names) * len(lib.SEEDS) * lib.FOLDS
    o.need(len(folds) == expected_rows, f"{fold_p}: {len(folds)} rows, expected {expected_rows}")
    for n in names:
        rows = [r for r in folds if r["features"] == n]
        o.need(len(rows) == len(lib.SEEDS) * lib.FOLDS, f"{fold_p}: {n} has {len(rows)} folds")
    s_meta, s_sum, s_cho = src(meta_p), src(sum_p), src(cho_p)
    src(fold_p)

    o.put("lastfm.machine", meta["host"], s_meta)
    des = meta["design"]
    o.put("design.degree_classes", meta["leakage_check"]["distinct_labels"], s_meta)
    o.put("design.rho_grid", ", ".join(str(r) for r in des["rho_w"]), s_meta)
    o.put("design.R_min", des["pattern_window"][0], s_meta)
    o.put("design.R_max", des["pattern_window"][1], s_meta)
    o.put("design.outer_folds", des["folds"], s_meta)
    seeds_run = sorted({int(r["seed"]) for r in folds})
    o.need(seeds_run == list(lib.SEEDS) and des["seeds"] == len(seeds_run),
           f"{fold_p}: seeds {seeds_run}, run record {des['seeds']}, design {list(lib.SEEDS)}")
    o.put("design.seeds", len(seeds_run), s_meta)
    o.put("design.inner_folds", des["inner_folds"], s_meta)
    o.put("design.limit_min", des["limit_ms"] / 60000, s_meta)
    # Software of the classification: the versions recorded by the run; the solver read from the model
    # that lib.model builds, with the scikit-learn version of the run (the export refuses another).
    o.put("design.env.python", meta["python"], s_meta)
    for pkg in ("scikit-learn", "scipy", "numpy"):
        o.put(f"design.env.{pkg.replace('-', '_')}", meta["packages"][pkg], s_meta)
    try:
        import sklearn
    except ImportError:
        sklearn = None
    o.need(sklearn is not None and sklearn.__version__ == meta["packages"]["scikit-learn"],
           f"the solver is read with scikit-learn {meta['packages']['scikit-learn']} (the run's); found "
           f"{getattr(sklearn, '__version__', 'none')}")
    if sklearn is not None:
        lr = lib.model(0).estimator.named_steps["lr"]
        o.put("design.env.solver", lr.get_params()["solver"], "tools/downstream/lib.py")
        o.put("design.env.max_iter", lr.get_params()["max_iter"], "tools/downstream/lib.py")
    o.put("lastfm.vertices", meta["leakage_check"]["vertices"], s_meta)
    o.put("lastfm.edges", meta["leakage_check"]["edges_compared"], s_meta)
    o.put("lastfm.classes", summ["classes"]["count"], s_sum)
    o.put("lastfm.smallest_class", summ["classes"]["smallest"], s_sum)
    o.put("lastfm.largest_class", summ["classes"]["largest"], s_sum)
    o.put("lastfm.label_equal_degree_class_vertices", meta["leakage_check"]["vertices_whose_label_equals_target"], s_meta)
    c = cho["chosen"]
    o.need(c is not None, f"{cho_p}: no chosen cell")
    for k in ("tau_s", "rho_w", "R", "frequent_max_edges", "frequent_patterns"):
        o.put(f"lastfm.chosen.{k}", c[k], s_cho)
    o.put("lastfm.chosen.tau_w", float(c["tau_w"]), s_cho)
    o.put("lastfm.scanned_cells", sum(1 for r in cho["scanned"] if r["status"] != "SKIPPED"), s_cho)
    o.put("lastfm.nesting.fws_in_frequent", summ["nesting"]["FWS_in_frequent"], s_sum)
    o.put("lastfm.nesting.all", summ["nesting"]["ALL"], s_sum)
    for name, _g in lib.PATTERN_SETS:
        dim = summ["dimensions"][name]
        k = NAMES[name]
        o.put(f"lastfm.set.{k}.patterns", dim["patterns"], s_sum)
        o.put(f"lastfm.set.{k}.columns_before", dim["columns_before_dedupe"], s_sum)
        o.put(f"lastfm.set.{k}.columns_after", dim["columns_after_dedupe"], s_sum)
        o.put(f"lastfm.set.{k}.empty", dim["empty"], s_sum)
        if not dim["empty"]:
            o.put(f"lastfm.set.{k}.vertices_with_a_feature", dim["vertices_with_a_feature"], s_sum)
    tim_p = os.path.join(d, "timings_measurement.json")
    tim = load(tim_p)
    o.need(tim.get("kind") == "measurement", f"{tim_p}: kind {tim.get('kind')!r}")
    s_tim = src(tim_p)
    for name, key in (("FWS", "fws"), ("Frequent (all)", "freq_all"), ("ALL", "all")):
        o.put(f"lastfm.time.mining_ms.{key}", float(tim["mining"][name]["totalMs"]), s_tim)
        o.put(f"lastfm.time.mining_runs.{key}", int(tim["mining"][name]["runs"]), s_tim)
    o.put("lastfm.time.image_sets_ms.fws", tim["image_set_ms"]["FWS"], s_tim)
    o.put("lastfm.time.image_sets_ms.freq_all", tim["image_set_ms"]["Frequent (all)"], s_tim)
    o.put("lastfm.time.classification_min", tim["classification_wall_ms"] / 60000, s_tim)
    o.put("lastfm.columns.B0", summ["dimensions"]["B0"], s_sum)
    o.put("lastfm.columns.B1", summ["dimensions"]["B1"], s_sum)
    for n in names:
        pc = summ["per_config"].get(n)
        o.need(pc is not None, f"{sum_p}: configuration {n} missing")
        if pc is None:
            continue
        k = config_key(n)
        o.put(f"lastfm.config.{k}.empty", pc["empty"], s_sum)
        if pc["empty"]:
            continue
        o.need(pc["folds"] == len(lib.SEEDS) * lib.FOLDS, f"{sum_p}: {n} has {pc['folds']} folds")
        for m in ("macro_f1_mean", "macro_f1_sd", "accuracy_mean", "accuracy_sd", "not_converged"):
            o.put(f"lastfm.config.{k}.{m}", pc[m], s_sum)
        o.put(f"lastfm.config.{k}.C_at_grid_top", pc["C_chosen"][str(lib.C_GRID[-1])], s_sum)
    tests = {}
    for t in summ["tests"]:
        if t.get("empty"):
            tests[(t["reference"], t["other"], "empty")] = t
        else:
            tests[(t["reference"], t["other"], t["metric"])] = t
    for base in lib.BASELINES:
        ref = f"{base} + {lib.REFERENCE}"
        for other in [base] + [f"{base} + {n}" for n, _g in lib.PATTERN_SETS if n != lib.REFERENCE]:
            k = f"lastfm.test.{config_key(ref)}_vs_{config_key(other)}"
            if (ref, other, "empty") in tests:
                o.put(k + ".empty", True, s_sum)
                continue
            for metric in ("macro_f1", "accuracy"):
                t = tests.get((ref, other, metric))
                o.need(t is not None, f"{sum_p}: test {ref} vs {other} [{metric}] missing")
                if t is None:
                    continue
                o.need(t["pairs"] == len(lib.SEEDS) * lib.FOLDS, f"{sum_p}: test {ref} vs {other} has {t['pairs']} pairs")
                for f_ in ("mean_difference", "t", "df", "p_value", "reference_better", "other_better", "pairs"):
                    o.put(f"{k}.{metric}.{f_}", t[f_], s_sum)

    # ---- GitHub: the scan outcome
    g = os.path.join(camp, "B8_downstream_github")
    gm_p, gc_p = os.path.join(g, "run_meta.json"), os.path.join(g, "downstream_choice.json")
    gm, gc = load(gm_p), load(gc_p)
    o.need(gm.get("kind") == "measurement" and gm.get("tree") == "clean" and gm.get("departures_from_design") == {},
           f"{gm_p}: not a clean measurement with the design settings")
    o.need(str(gm.get("outcome", "")).startswith("no cell chosen"), f"{gm_p}: outcome {gm.get('outcome')!r}")
    s_gm, s_gc = src(gm_p), src(gc_p)
    o.put("github.outcome", "no cell chosen", s_gm)
    o.put("github.vertices", gm["leakage_check"]["vertices"], s_gm)
    o.put("github.edges", gm["leakage_check"]["edges_compared"], s_gm)
    o.put("github.selection_limit_min", gm["design"]["selection_limit_ms"] / 60000, s_gm)
    last = [r for r in gc["scanned"] if r["status"] != "SKIPPED"][-1]
    o.put("github.stop.tau_s", last["tau_s"], s_gc)
    o.put("github.stop.rho_w", last["rho_w"], s_gc)
    o.put("github.stop.tau_w", float(last["tau_w"]), s_gc)
    o.put("github.stop.status", last["status"], s_gc)
    o.put("github.stop.R_lower_bound", last["R"], s_gc)
    o.need(last["decision"] == "undecided", f"{gc_p}: last scanned cell decided {last['decision']!r}")

    if probe is not None:
        pr = load(probe)
        sp = src(probe)
        hi = max(pr["grid"]["rho_w"])
        cells = [r for r in pr["scanned"] if r["rho_w"] == hi]
        o.need(cells and all(r["status"] == "OK" for r in cells), f"{probe}: a cell of rho_w {hi} did not complete")
        o.need(pr["grid"]["rho_w"] == gm["design"]["rho_w"], f"{probe}: rho_w grid differs from the measured run")
        o.put("github.probe.rho_high", hi, sp)
        o.put("github.probe.rho_high_cells", len(cells), sp)
        o.put("github.probe.rho_high_max_R", max(r["R"] for r in cells), sp)
        o.put("github.probe.rho_high_min_tau_s", min(r["tau_s"] for r in cells), sp)

    # ---- baseline checks (B0 against chance)
    for ds in ("lastfm", "email_eu", "github"):
        p = os.path.join(checks, ds + ".json")
        r = load(p)
        sp = src(p)
        o.need(r["run"]["tree"] == "clean", f"{p}: tree {r['run']['tree']!r}")
        o.need(r["fold_rows"] == len(lib.SEEDS) * lib.FOLDS, f"{p}: {r['fold_rows']} folds")
        for k in ("classes_total", "classes_kept", "vertices_total", "vertices_kept", "B0_macro_f1_mean",
                  "B0_macro_f1_sd", "B0_folds_above_prior_chance", "fold_rows"):
            o.put(f"check.{ds}.{k}", r[k], sp)
        o.put(f"check.{ds}.chance_uniform_macro_f1", r["chance"]["uniform_macro_f1"], sp)
        o.put(f"check.{ds}.min_class_size", r["min_class"], sp)
        o.put(f"check.{ds}.same_class_edge_share", r["same_class_edge_share"], sp)
        o.put(f"check.{ds}.same_class_edge_share_random", r["same_class_edge_share_random"], sp)
        o.put(f"check.{ds}.chance_prior_macro_f1", r["chance"]["prior_macro_f1"], sp)
    o.need(o.values.get("check.lastfm.B0_macro_f1_mean") == o.values.get("lastfm.config.B0.macro_f1_mean"),
           "the baseline check does not reproduce the B0 of the reported LastFM run exactly")

    if o.problems:
        for p in o.problems:
            print("ERROR: " + p, file=sys.stderr)
        sys.exit(1)
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        json.dump({"values": o.values, "sources": o.sources, "files": files}, f, indent=2, sort_keys=True)
    print(f"export_numbers: {len(o.values)} values from {len(files)} files -> {out_path}")


if __name__ == "__main__":
    main()
