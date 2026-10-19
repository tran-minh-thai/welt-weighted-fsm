#!/usr/bin/env python3
"""Downstream node classification with WeLT patterns on LastFM Asia (one command).

    python tools/downstream/b6.py [--out DIR] [--kind probe|measurement] [--voting voting_choice.json]
                                  [--limit-ms N] [--selection-limit-ms N] [--warmup W] [--measured M]
                                  [--skip-build]

Steps (README.md in this folder gives the design and the leakage controls):
  1. data      fetch the raw MUSAE archive if missing, verify every SHA-256, rebuild
               datasets/lastfm_struct.lg and compare it with the committed file and with lastfm.lg;
  2. select    scan the fixed (tau_s, rho_w) grid with WeLT (BenchmarkMain) on lastfm_struct.lg and
               keep the first cell whose result set has 20..300 patterns (no class label involved);
  3. mine      the chosen cell with the timing protocol (warmup, measured runs), patterns dumped;
  4. images    ImageSetMain: image sets of every pattern vertex on the graph filtered at tau_w;
  5. classify  logistic regression, stratified 10-fold x 3 seeds, three feature sets;
  6. record    selection.json, folds.csv, summary.json, timings_<kind>.json, run_meta.json.

Timings depend on the machine: --kind probe (the default) marks them as a feasibility probe;
--kind measurement is for the measurement machine only (revision.sh b6). The quality metrics
do not depend on the machine.
"""
import argparse
import csv
import datetime
import importlib.metadata
import json
import os
import platform
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)
import b6lib  # noqa: E402

DATA = os.path.join(ROOT, "datasets")
RAW = os.path.join(DATA, "raw")
LASTFM_RAW = os.path.join(RAW, "lasftm_asia")
STRUCT_LG = os.path.join(DATA, "lastfm_struct.lg")
REF_LG = os.path.join(DATA, "lastfm.lg")
CPSEP = ";" if os.name == "nt" else ":"
TOOL_FILES = [
    "tools/downstream/b6.py", "tools/downstream/b6lib.py", "tools/downstream/selftest.py",
    "tools/downstream/requirements.txt", "datasets/convert_musae_struct.py",
    "src/main/java/welt/runner/ImageSetMain.java", "src/main/java/welt/core/MniSupportCounter.java",
    "src/main/java/welt/runner/BenchmarkMain.java", "src/main/java/welt/runner/PatternDump.java",
]


class Log:
    def __init__(self, path):
        self.fh = open(path, "w", encoding="utf-8", newline="\n")

    def __call__(self, msg=""):
        print(msg, flush=True)
        self.fh.write(msg + "\n")
        self.fh.flush()


def fail(log, msg, code=1):
    log("ERROR: " + msg)
    sys.exit(code)


def rel(path):
    return os.path.relpath(path, ROOT).replace(os.sep, "/")


def check_requirements(log):
    pins = {}
    with open(os.path.join(HERE, "requirements.txt")) as f:
        for line in f:
            line = line.split("#")[0].strip()
            if line:
                name, ver = line.split("==")
                pins[name.strip()] = ver.strip()
    bad = []
    for name, ver in pins.items():
        try:
            got = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            got = None
        if got != ver:
            bad.append(f"{name}: required {ver}, found {got}")
    if bad:
        fail(log, "Python packages differ from tools/downstream/requirements.txt (" + "; ".join(bad)
             + "). Install them with: python -m pip install -r tools/downstream/requirements.txt")
    return pins


def git(*args):
    try:
        return subprocess.run(["git", "-C", ROOT, *args], capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def java_version():
    p = subprocess.run(["java", "-XshowSettings:properties", "-version"], capture_output=True, text=True)
    props = dict(re.findall(r"^\s*(java\.(?:vendor|runtime\.version)) = (.*)$", p.stderr, re.M))
    return props


# ---------------------------------------------------------------- step 1: data

def ensure_raw(log):
    missing = [m for m in b6lib.LASTFM_MEMBERS if not os.path.isfile(os.path.join(RAW, m))]
    if missing:
        zpath = os.path.join(RAW, "lastfm_asia.zip")
        os.makedirs(RAW, exist_ok=True)
        if not os.path.isfile(zpath):
            log(f"  downloading {b6lib.LASTFM_ZIP_URL}")
            with urllib.request.urlopen(b6lib.LASTFM_ZIP_URL, timeout=120) as r, open(zpath + ".part", "wb") as out:
                shutil.copyfileobj(r, out)
            os.replace(zpath + ".part", zpath)
        got = b6lib.sha256(zpath)
        if got != b6lib.LASTFM_ZIP_SHA:
            fail(log, f"{rel(zpath)} has SHA-256 {got}, expected {b6lib.LASTFM_ZIP_SHA}")
        with zipfile.ZipFile(zpath) as z:
            for m in b6lib.LASTFM_MEMBERS:
                z.extract(m, RAW)
    for m, sha in b6lib.LASTFM_MEMBERS.items():
        got = b6lib.sha256(os.path.join(RAW, m))
        if got != sha:
            fail(log, f"datasets/raw/{m} has SHA-256 {got}, expected {sha}")
    log("  raw LastFM Asia files: SHA-256 verified")


def build_struct_graph(log, out_dir):
    for path, sha in ((REF_LG, b6lib.LASTFM_LG_SHA), (STRUCT_LG, b6lib.LASTFM_STRUCT_LG_SHA)):
        got = b6lib.sha256(path)
        if got != sha:
            fail(log, f"{rel(path)} has SHA-256 {got}, expected {sha}")
    rebuilt = os.path.join(out_dir, "lastfm_struct.rebuilt.lg")
    p = subprocess.run([sys.executable, os.path.join(DATA, "convert_musae_struct.py"),
                        os.path.join(LASTFM_RAW, "lastfm_asia_edges.csv"),
                        os.path.join(LASTFM_RAW, "lastfm_asia_features.json"), rebuilt,
                        "--check-against", REF_LG], capture_output=True, text=True)
    for line in (p.stdout + p.stderr).splitlines():
        log("  " + line.replace(out_dir, "<out>").replace(ROOT, "<repo>"))
    if p.returncode != 0:
        fail(log, "convert_musae_struct.py failed its check against lastfm.lg")
    if b6lib.sha256(rebuilt) != b6lib.LASTFM_STRUCT_LG_SHA:
        fail(log, "the rebuilt lastfm_struct.lg differs from the committed one")
    os.remove(rebuilt)
    log("  lastfm_struct.lg rebuilt from the raw files: identical to the committed file")

    s_labels, s_edges = b6lib.read_lg(STRUCT_LG)
    r_labels, r_edges = b6lib.read_lg(REF_LG)
    target = b6lib.read_target(os.path.join(LASTFM_RAW, "lastfm_asia_target.csv"))
    ok, facts = b6lib.leakage_report(s_labels, s_edges, r_labels, r_edges, target)
    log("  leakage check: " + json.dumps(facts))
    if not ok:
        fail(log, "the mining graph is not lastfm.lg with degree-class labels")
    return s_labels, s_edges, target, facts


# ---------------------------------------------------------------- steps 2-3: mining

def run_bench(log, tau_s, tau_w, dump_dir, limit_ms, warmup, measured, env_extra):
    env = dict(os.environ, BENCH_CSV="1", BENCH_ALGOS="WeLT", DUMP_PATTERNS=dump_dir, **env_extra)
    cmd = ["java", *os.environ.get("JAVA_OPTS", "").split(), "-cp", os.path.join(ROOT, "target", "classes"),
           "welt.runner.BenchmarkMain", STRUCT_LG, str(tau_s), tau_w, "0", str(limit_ms), str(warmup), str(measured)]
    p = subprocess.run(cmd, capture_output=True, text=True, env=env, cwd=ROOT)
    out = p.stdout + p.stderr
    rows = [line[4:] for line in out.splitlines() if line.startswith("CSV,")]
    if p.returncode not in (0, 3) or len(rows) < 2:
        log(out)
        fail(log, f"BenchmarkMain exited with {p.returncode} for tau_s={tau_s} tau_w={tau_w}")
    row = next(csv.DictReader(rows))
    dumps = [f for f in os.listdir(dump_dir) if f.endswith(".tsv")] if os.path.isdir(dump_dir) else []
    return row, (os.path.join(dump_dir, dumps[0]) if len(dumps) == 1 else None), " ".join(cmd[:1] + cmd[-9:])


def grid_tau_w(log):
    p = subprocess.run(["java", "-cp", os.path.join(ROOT, "target", "classes"), "welt.runner.SharedTableSweepMain",
                        "--grid", STRUCT_LG, ",".join(str(r) for r in b6lib.RHO_W)],
                       capture_output=True, text=True, cwd=ROOT)
    vals = p.stdout.split()
    if p.returncode != 0 or len(vals) != len(b6lib.RHO_W):
        fail(log, "SharedTableSweepMain --grid failed: " + p.stdout + p.stderr)
    return dict(zip(b6lib.RHO_W, vals))


def is_timing_column(name):
    """A time or memory measurement of BenchmarkMain (limitMs is a setting, not a measurement)."""
    return (name.endswith("Ms") or "Mem" in name) and name != "limitMs"


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", help="output directory (default results-probe/b6/<date>_<host>)")
    ap.add_argument("--kind", choices=("probe", "measurement"), default="probe")
    ap.add_argument("--voting", help="voting_choice.json of the campaign (WELT_VOTER, WELT_WORDER)")
    ap.add_argument("--limit-ms", type=int, default=3600000, help="time limit of one mining run of the chosen cell")
    ap.add_argument("--selection-limit-ms", type=int, default=600000,
                    help="time limit of one selection run (only its pattern count is used; see b6lib.scan)")
    ap.add_argument("--warmup", type=int, default=1)
    ap.add_argument("--measured", type=int, default=5)
    ap.add_argument("--skip-build", action="store_true", help="target/classes is already built")
    a = ap.parse_args()

    host = re.sub(r"[^A-Za-z0-9_.-]", "_", socket.gethostname())
    started = datetime.datetime.now()
    out = os.path.abspath(a.out or os.path.join(ROOT, "results-probe", "b6", started.strftime("%Y%m%d_%H%M") + "_" + host))
    if os.path.exists(out) and os.listdir(out):
        sys.exit(f"ERROR: {out} exists and is not empty; choose another --out")
    os.makedirs(out, exist_ok=True)
    log = Log(os.path.join(out, "console.txt"))
    log(f"== B6 downstream classification | kind={a.kind} | out={rel(out)}")

    pins = check_requirements(log)
    st = subprocess.run([sys.executable, os.path.join(HERE, "selftest.py")], capture_output=True, text=True)
    log("  " + st.stdout.strip().splitlines()[-1] if st.stdout.strip() else "  selftest: no output")
    if st.returncode != 0:
        log(st.stdout + st.stderr)
        fail(log, "tools/downstream/selftest.py failed")
    if not a.skip_build:
        b = subprocess.run(["bash", os.path.join(ROOT, "build.sh")], capture_output=True, text=True)
        if b.returncode != 0:
            log(b.stdout + b.stderr)
            fail(log, "build.sh failed")
    tree = git("status", "--porcelain", "--untracked-files=no")
    meta = {
        "experiment": "B6 downstream node classification",
        "kind": a.kind,
        "command": " ".join([os.path.basename(sys.executable), rel(os.path.abspath(sys.argv[0])), *sys.argv[1:]]),
        "started": started.isoformat(timespec="seconds"),
        "git_commit": git("rev-parse", "HEAD"),
        "tree": "clean" if tree == "" else (f"MODIFIED ({len(tree.splitlines())} files)" if tree else "unknown"),
        "host": host, "os": platform.platform(), "machine": platform.machine(),
        "python": platform.python_version(), "packages": pins, "java": java_version(),
        "java_opts": os.environ.get("JAVA_OPTS", ""),
        "tool_sha256": {f: b6lib.sha256(os.path.join(ROOT, f)) for f in TOOL_FILES},
        "design": {"tau_s_grid": b6lib.TAU_S_GRID, "rho_w": b6lib.RHO_W, "pattern_window": b6lib.PATTERN_WINDOW,
                   "seeds": b6lib.SEEDS, "folds": b6lib.FOLDS, "max_iter": b6lib.MAX_ITER,
                   "limit_ms": a.limit_ms, "selection_limit_ms": a.selection_limit_ms,
                   "warmup": a.warmup, "measured": a.measured},
    }
    if a.kind == "measurement" and meta["tree"] != "clean":
        fail(log, "a measurement run needs a clean working tree (the commit must describe the code that ran)")

    env_extra = {}
    if a.voting:
        with open(a.voting) as f:
            vc = json.load(f)
        env_extra = {"WELT_VOTER": str(vc["voter"]), "WELT_WORDER": str(vc["worder"])}
        meta["voting_choice"] = {"file": os.path.basename(a.voting), "sha256": b6lib.sha256(a.voting), **env_extra}
    log("  WeLT voting: " + (json.dumps(env_extra) if env_extra else "runner default (WELT_VOTER unset)"))

    # 1. data
    log("== 1. data")
    ensure_raw(log)
    s_labels, s_edges, target, facts = build_struct_graph(log, out)
    meta["data_sha256"] = {rel(STRUCT_LG): b6lib.LASTFM_STRUCT_LG_SHA, rel(REF_LG): b6lib.LASTFM_LG_SHA,
                           **{"datasets/raw/" + m: s for m, s in b6lib.LASTFM_MEMBERS.items()}}
    meta["leakage_check"] = facts

    # 2. threshold selection (one warmup and one measured run per cell: only |R| is used)
    log("== 2. threshold selection")
    tw = grid_tau_w(log)
    log("  tau_w = min + rho_w * (max - min): " + json.dumps(tw))
    selection_ms = []

    def run_selection_cell(tau_s, tau_w):
        rho = next(r for r, t in tw.items() if t == tau_w)
        d = os.path.join(out, "selection_patterns", f"{tau_s}_{rho}")
        row, _dump, _ = run_bench(log, tau_s, tau_w, d, a.selection_limit_ms, 1, 1, env_extra)
        n = int(row["R"])
        selection_ms.append({"tau_s": tau_s, "rho_w": rho, "status": row["status"], "totalMs": row["totalMs"]})
        log(f"  tau_s={tau_s:<5} rho_w={rho} tau_w={tau_w}: status={row['status']} |R|={n}")
        return row["status"], n

    chosen, reason, grid_rows = b6lib.scan(b6lib.selection_order(tw), run_selection_cell)
    skipped = sum(1 for r in grid_rows if r["status"] == "SKIPPED")
    if skipped:
        log(f"  {skipped} cells skipped: a higher tau_s at their rho_w is already above the window")
    selection = {"rule": f"first (tau_s descending, rho_w in {list(b6lib.RHO_W)}) pair with "
                         f"{b6lib.PATTERN_WINDOW[0]} <= |R| <= {b6lib.PATTERN_WINDOW[1]}; no class label is used",
                 "grid": {"tau_s": b6lib.TAU_S_GRID, "rho_w": b6lib.RHO_W},
                 "selection_run": {"limit_ms": a.selection_limit_ms, "warmup": 1, "measured": 1},
                 "tau_w_of_rho_w": tw, "scanned": grid_rows, "reason": reason, "chosen": None}
    if chosen is None:
        with open(os.path.join(out, "selection.json"), "w", newline="\n") as f:
            json.dump(selection, f, indent=2)
        fail(log, reason, code=2)
    c = grid_rows[chosen]
    selection["chosen"] = {"tau_s": c["tau_s"], "rho_w": c["rho_w"], "tau_w": c["tau_w"], "R": c["R"]}
    with open(os.path.join(out, "selection.json"), "w", newline="\n") as f:
        json.dump(selection, f, indent=2)
    log("  chosen: " + reason)

    # 3. mining of the chosen cell with the timing protocol
    log("== 3. mining the chosen cell")
    pat_dir = os.path.join(out, "patterns")
    row, dump, cmd = run_bench(log, c["tau_s"], c["tau_w"], pat_dir, a.limit_ms, a.warmup, a.measured, env_extra)
    if row["status"] != "OK" or dump is None or int(row["R"]) != c["R"]:
        fail(log, f"the timed run of the chosen cell ended with status {row['status']}, |R|={row['R']} "
                  f"(selection run: |R|={c['R']})")
    # counts only: every time and memory column goes to timings_<kind>.json
    counts = {k: v for k, v in row.items() if not is_timing_column(k)}
    with open(os.path.join(out, "mining.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(counts), lineterminator="\n")
        w.writeheader()
        w.writerow(counts)
    log(f"  |R|={row['R']} totalMs median={row['totalMs']} over {row['runs']} runs (warmup {row['warmup']})")

    # 4. image sets on the filtered graph
    log("== 4. image sets")
    img_path = os.path.join(out, "image_sets.tsv")
    t0 = time.perf_counter()
    p = subprocess.run(["java", *os.environ.get("JAVA_OPTS", "").split(), "-cp", os.path.join(ROOT, "target", "classes"),
                        "welt.runner.ImageSetMain", STRUCT_LG, c["tau_w"], dump, img_path],
                       capture_output=True, text=True, cwd=ROOT)
    image_wall_ms = (time.perf_counter() - t0) * 1000
    log("  " + (p.stdout + p.stderr).strip().replace(out, "<out>"))
    if p.returncode != 0:
        fail(log, "ImageSetMain failed")
    image_ms = float(re.search(r"imageSetMs=([0-9.]+)", p.stdout).group(1))

    # 5. features and classification
    log("== 5. features and classification")
    import numpy as np
    from scipy import sparse
    n = len(s_labels)
    t0 = time.perf_counter()
    sets = b6lib.read_image_sets(img_path)
    pat = b6lib.pattern_matrix(n, sets)
    assemble_ms = (time.perf_counter() - t0) * 1000
    base, n_binary = b6lib.baseline_matrix(os.path.join(LASTFM_RAW, "lastfm_asia_features.json"), n, s_edges)
    matrices = {"baseline": base, "baseline+patterns": sparse.hstack([base, pat]).tocsr(), "patterns": pat}
    dims = {
        "baseline": base.shape[1], "baseline_binary": n_binary, "baseline_structural": 2,
        "patterns": pat.shape[1], "patterns_distinct_columns": b6lib.distinct_columns(pat),
        "patterns_count": len({p for p, _u, _i in sets}),
        "patterns_nonzeros": int(pat.nnz),
        "vertices_with_a_pattern_feature": int(np.sum(np.asarray(pat.sum(axis=1)).ravel() > 0)),
        "baseline+patterns": matrices["baseline+patterns"].shape[1],
    }
    log("  dimensions: " + json.dumps(dims))
    y = np.asarray(target)
    t0 = time.perf_counter()
    rows = b6lib.evaluate(matrices, y, log=log)
    classify_ms = (time.perf_counter() - t0) * 1000
    with open(os.path.join(out, "folds.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]), lineterminator="\n")
        w.writeheader()
        w.writerows(rows)
    summary = b6lib.summarise(rows)
    summary["dimensions"] = dims
    summary["classes"] = {"count": int(len(set(target))), "smallest": int(min(np.bincount(y))),
                          "largest": int(max(np.bincount(y)))}
    summary["chosen_cell"] = selection["chosen"]
    with open(os.path.join(out, "summary.json"), "w", newline="\n") as f:
        json.dump(summary, f, indent=2)
    for name, s in summary["per_set"].items():
        log(f"  {name:<18} macro-F1 {s['macro_f1_mean']:.4f} ({s['macro_f1_sd']:.4f})   "
            f"accuracy {s['accuracy_mean']:.4f} ({s['accuracy_sd']:.4f})   not converged {s['not_converged']}/{s['folds']}")
    for k, wv in summary["wilcoxon"].items():
        log(f"  Wilcoxon {k}: {wv['compared']}: mean diff {wv['mean_difference']:+.4f}, "
            f"{wv['b_better']}/{wv['pairs']} folds better, {wv['zero_differences']} ties, p={wv['p_value']}")

    # 6. timings and run record
    timings = {
        "kind": a.kind,
        "note": ("feasibility probe: timings from a development machine, not a measurement"
                 if a.kind == "probe" else "measurement-machine run (revision.sh b6)"),
        "host": host,
        "mining": {k: row[k] for k in ("runs", "warmup", *[c for c in row if is_timing_column(c)])},
        "selection_runs": selection_ms,
        "mining_command": cmd.replace(ROOT, "<repo>"),
        "feature_building": {"imageSetMs": image_ms, "imageSetProcessWallMs": round(image_wall_ms, 1),
                             "matrixAssemblyMs": round(assemble_ms, 1),
                             "patternFeaturesTotalMs": round(image_ms + assemble_ms, 1)},
        "classification_wall_ms": round(classify_ms, 1),
        "feature_dimension": dims,
    }
    with open(os.path.join(out, f"timings_{a.kind}.json"), "w", newline="\n") as f:
        json.dump(timings, f, indent=2)
    meta["finished"] = datetime.datetime.now().isoformat(timespec="seconds")
    meta["outputs"] = sorted(rel(os.path.join(dp, fn)).replace(rel(out) + "/", "")
                             for dp, _dn, fns in os.walk(out) for fn in fns)
    with open(os.path.join(out, "run_meta.json"), "w", newline="\n") as f:
        json.dump(meta, f, indent=2)
    log(f"== done -> {rel(out)}")


if __name__ == "__main__":
    main()
