#!/usr/bin/env python3
"""Downstream node classification with mined patterns on LastFM Asia (one command).

    python tools/downstream/run.py [--out DIR] [--kind probe|measurement] [--voting voting_choice.json]
                                   [--limit-ms N] [--selection-limit-ms N] [--check-limit-ms N]
                                   [--frequent-bound fws-edges|none] [--warmup W] [--measured M]
                                   [--jobs J] [--seeds S] [--folds K] [--skip-build]

Steps (README.md in this folder gives the design and the leakage controls):
  1. data      fetch the raw MUSAE archive if missing, verify every SHA-256, rebuild
               datasets/lastfm_struct.lg and compare it with the committed file and with lastfm.lg;
  2. select    scan the fixed (tau_s, rho_w) grid: a cell is chosen when WeLT returns 20..300
               frequent weighted subgraphs (FWS) and GraMi completes at the same tau_s within
               --check-limit-ms, mining patterns of at most as many edges as the largest FWS
               (--frequent-bound fws-edges) or of any size (--frequent-bound none); no class label
               is used; the choice is written to downstream_choice.json before any feature exists;
  3. mine      at the chosen cell, with the timing protocol: WeLT (FWS), GraMi (frequent patterns)
               and GraMi on the graph of the edges of weight >= tau_w (the exact ALL set);
  4. images    ImageSetMain: image sets of every pattern vertex in G, and for ALL also in the
               graph of the heavy edges;
  5. classify  logistic regression, C chosen by inner cross-validation, stratified 10-fold x 3
               seeds, twelve configurations (lib.config_names), corrected paired t-tests;
  6. record    folds.csv, summary_configs.csv, summary_tests.csv, summary.json, the pattern
               lists, timings_<kind>.json, run_meta.json.

Timings depend on the machine: --kind probe (the default) marks them as a feasibility probe;
--kind measurement is for the measurement machine only (revision.sh downstream) and refuses
every option that departs from the design. The quality metrics do not depend on the machine.
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
import lib  # noqa: E402

DATA = os.path.join(ROOT, "datasets")
RAW = os.path.join(DATA, "raw")
LASTFM_RAW = os.path.join(RAW, "lasftm_asia")
STRUCT_LG = os.path.join(DATA, "lastfm_struct.lg")
REF_LG = os.path.join(DATA, "lastfm.lg")
TOOL_FILES = [
    "tools/downstream/run.py", "tools/downstream/lib.py", "tools/downstream/selftest.py",
    "tools/downstream/requirements.txt", "datasets/convert_musae_struct.py", "tools/heavy_subgraph.py",
    "src/main/java/welt/runner/ImageSetMain.java", "src/main/java/welt/core/MniSupportCounter.java",
    "src/main/java/welt/runner/BenchmarkMain.java", "src/main/java/welt/runner/PatternDump.java",
    "src/main/java/welt/core/MiningEngine.java",
]
DESIGN_DEFAULTS = {"limit_ms": 3600000, "selection_limit_ms": 600000, "check_limit_ms": 3600000,
                   "frequent_bound": "fws-edges", "warmup": 1, "measured": 5,
                   "seeds": len(lib.SEEDS), "folds": lib.FOLDS}


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


def write_json(path, obj):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=2)


def write_csv(path, rows):
    with open(path, "w", newline="") as f:
        fields = list(dict.fromkeys(k for r in rows for k in r))
        w = csv.DictWriter(f, fieldnames=fields, lineterminator="\n")
        w.writeheader()
        w.writerows(rows)


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
    return dict(re.findall(r"^\s*(java\.(?:vendor|runtime\.version)) = (.*)$", p.stderr, re.M))


def java(cls, *args):
    return ["java", *os.environ.get("JAVA_OPTS", "").split(), "-cp", os.path.join(ROOT, "target", "classes"),
            cls, *args]


# ---------------------------------------------------------------- step 1: data

def ensure_raw(log):
    missing = [m for m in lib.LASTFM_MEMBERS if not os.path.isfile(os.path.join(RAW, m))]
    if missing:
        zpath = os.path.join(RAW, "lastfm_asia.zip")
        os.makedirs(RAW, exist_ok=True)
        if not os.path.isfile(zpath):
            log(f"  downloading {lib.LASTFM_ZIP_URL}")
            with urllib.request.urlopen(lib.LASTFM_ZIP_URL, timeout=120) as r, open(zpath + ".part", "wb") as out:
                shutil.copyfileobj(r, out)
            os.replace(zpath + ".part", zpath)
        got = lib.sha256(zpath)
        if got != lib.LASTFM_ZIP_SHA:
            fail(log, f"{rel(zpath)} has SHA-256 {got}, expected {lib.LASTFM_ZIP_SHA}")
        with zipfile.ZipFile(zpath) as z:
            for m in lib.LASTFM_MEMBERS:
                z.extract(m, RAW)
    for m, sha in lib.LASTFM_MEMBERS.items():
        got = lib.sha256(os.path.join(RAW, m))
        if got != sha:
            fail(log, f"datasets/raw/{m} has SHA-256 {got}, expected {sha}")
    log("  raw LastFM Asia files: SHA-256 verified")


def build_struct_graph(log, out_dir):
    for path, sha in ((REF_LG, lib.LASTFM_LG_SHA), (STRUCT_LG, lib.LASTFM_STRUCT_LG_SHA)):
        got = lib.sha256(path)
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
    if lib.sha256(rebuilt) != lib.LASTFM_STRUCT_LG_SHA:
        fail(log, "the rebuilt lastfm_struct.lg differs from the committed one")
    os.remove(rebuilt)
    log("  lastfm_struct.lg rebuilt from the raw files: identical to the committed file")

    s_labels, s_edges = lib.read_lg(STRUCT_LG)
    r_labels, r_edges = lib.read_lg(REF_LG)
    target = lib.read_target(os.path.join(LASTFM_RAW, "lastfm_asia_target.csv"))
    ok, facts = lib.leakage_report(s_labels, s_edges, r_labels, r_edges, target)
    log("  leakage check: " + json.dumps(facts))
    if not ok:
        fail(log, "the mining graph is not lastfm.lg with degree-class labels")
    return s_labels, s_edges, target, facts


# ---------------------------------------------------------------- mining

def run_bench(log, graph, algo, tau_s, tau_w, dump_dir, limit_ms, warmup, measured, env_extra, max_edges=0):
    """One BenchmarkMain cell: (CSV row, path of the pattern dump or None, command line)."""
    env = dict(os.environ, BENCH_CSV="1", BENCH_ALGOS=algo, DUMP_PATTERNS=dump_dir,
               WELT_MAX_EDGES=str(max_edges), **env_extra)
    cmd = java("welt.runner.BenchmarkMain", graph, str(tau_s), tau_w, "0", str(limit_ms), str(warmup), str(measured))
    p = subprocess.run(cmd, capture_output=True, text=True, env=env, cwd=ROOT)
    out = p.stdout + p.stderr
    rows = [line[4:] for line in out.splitlines() if line.startswith("CSV,")]
    if p.returncode not in (0, 3) or len(rows) < 2:
        log(out)
        fail(log, f"BenchmarkMain {algo} exited with {p.returncode} for tau_s={tau_s} tau_w={tau_w}")
    row = next(csv.DictReader(rows))
    dumps = [f for f in os.listdir(dump_dir) if f.endswith(".tsv")] if os.path.isdir(dump_dir) else []
    shown = f"WELT_MAX_EDGES={max_edges} BENCH_ALGOS={algo} " + " ".join(cmd[:1] + cmd[-9:])
    return row, (os.path.join(dump_dir, dumps[0]) if len(dumps) == 1 else None), shown.replace(ROOT, "<repo>")


def grid_tau_w(log):
    p = subprocess.run(java("welt.runner.SharedTableSweepMain", "--grid", STRUCT_LG, ",".join(str(r) for r in lib.RHO_W)),
                       capture_output=True, text=True, cwd=ROOT)
    vals = p.stdout.split()
    if p.returncode != 0 or len(vals) != len(lib.RHO_W):
        fail(log, "SharedTableSweepMain --grid failed: " + p.stdout + p.stderr)
    return dict(zip(lib.RHO_W, vals))


def is_timing_column(name):
    """A time or memory measurement of BenchmarkMain (limitMs is a setting, not a measurement)."""
    return (name.endswith("Ms") or "Mem" in name) and name != "limitMs"


def counts_only(row):
    return {k: v for k, v in row.items() if not is_timing_column(k)}


def image_sets(log, dump, tau_w, mode, out_path):
    """ImageSetMain on a pattern dump: (rows of lib.read_image_sets, imageSetMs)."""
    p = subprocess.run(java("welt.runner.ImageSetMain", STRUCT_LG, tau_w, dump, out_path, mode),
                       capture_output=True, text=True, cwd=ROOT)
    log("  " + (p.stdout + p.stderr).strip().replace(os.path.dirname(out_path), "<out>").replace(ROOT, "<repo>"))
    if p.returncode != 0:
        fail(log, f"ImageSetMain ({mode}) failed on {os.path.basename(dump)}")
    return lib.read_image_sets(out_path), float(re.search(r"imageSetMs=([0-9.]+)", p.stdout).group(1))


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", help="output directory (default results-probe/downstream/<date>_<host>)")
    ap.add_argument("--kind", choices=("probe", "measurement"), default="probe")
    ap.add_argument("--voting", help="voting_choice.json of the campaign (WELT_VOTER, WELT_WORDER)")
    ap.add_argument("--limit-ms", type=int, default=DESIGN_DEFAULTS["limit_ms"],
                    help="time limit of one timed mining run at the chosen cell")
    ap.add_argument("--selection-limit-ms", type=int, default=DESIGN_DEFAULTS["selection_limit_ms"],
                    help="time limit of one WeLT selection run (only its pattern count is used; see lib.scan)")
    ap.add_argument("--check-limit-ms", type=int, default=DESIGN_DEFAULTS["check_limit_ms"],
                    help="time limit of the GraMi run of the second selection condition")
    ap.add_argument("--frequent-bound", choices=("fws-edges", "none"), default=DESIGN_DEFAULTS["frequent_bound"],
                    help="fws-edges: GraMi mines patterns of at most the largest FWS edge count; none: any size")
    ap.add_argument("--warmup", type=int, default=DESIGN_DEFAULTS["warmup"])
    ap.add_argument("--measured", type=int, default=DESIGN_DEFAULTS["measured"])
    ap.add_argument("--jobs", type=int, default=-1, help="processes of the inner C search (results do not depend on it)")
    ap.add_argument("--seeds", type=int, default=DESIGN_DEFAULTS["seeds"], help="probe only: first S seeds of lib.SEEDS")
    ap.add_argument("--folds", type=int, default=DESIGN_DEFAULTS["folds"], help="probe only: outer folds")
    ap.add_argument("--skip-build", action="store_true", help="target/classes is already built")
    a = ap.parse_args()

    host = re.sub(r"[^A-Za-z0-9_.-]", "_", socket.gethostname())
    started = datetime.datetime.now()
    out = os.path.abspath(a.out or os.path.join(ROOT, "results-probe", "downstream",
                                                started.strftime("%Y%m%d_%H%M") + "_" + host))
    if os.path.exists(out) and os.listdir(out):
        sys.exit(f"ERROR: {out} exists and is not empty; choose another --out")
    os.makedirs(out, exist_ok=True)
    log = Log(os.path.join(out, "console.txt"))
    log(f"== downstream node classification | kind={a.kind} | out={rel(out)}")

    settings = {"limit_ms": a.limit_ms, "selection_limit_ms": a.selection_limit_ms,
                "check_limit_ms": a.check_limit_ms, "frequent_bound": a.frequent_bound,
                "warmup": a.warmup, "measured": a.measured, "seeds": a.seeds, "folds": a.folds}
    departures = {k: v for k, v in settings.items() if v != DESIGN_DEFAULTS[k]}
    if a.kind == "measurement" and {k for k in departures if k != "frequent_bound"}:
        fail(log, "a measurement run uses the design settings; these differ: " + json.dumps(departures))
    if not 1 <= a.seeds <= len(lib.SEEDS) or a.folds < 2:
        fail(log, f"--seeds must be in 1..{len(lib.SEEDS)} and --folds at least 2")
    seeds = lib.SEEDS[:a.seeds]

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
        "experiment": "downstream node classification",
        "kind": a.kind,
        "command": " ".join([os.path.basename(sys.executable), rel(os.path.abspath(sys.argv[0])), *sys.argv[1:]]),
        "started": started.isoformat(timespec="seconds"),
        "git_commit": git("rev-parse", "HEAD"),
        "tree": "clean" if tree == "" else (f"MODIFIED ({len(tree.splitlines())} files)" if tree else "unknown"),
        "host": host, "os": platform.platform(), "machine": platform.machine(),
        "python": platform.python_version(), "packages": pins, "java": java_version(),
        "java_opts": os.environ.get("JAVA_OPTS", ""),
        "tool_sha256": {f: lib.sha256(os.path.join(ROOT, f)) for f in TOOL_FILES},
        "design": {"tau_s_grid": lib.TAU_S_GRID, "rho_w": lib.RHO_W, "pattern_window": lib.PATTERN_WINDOW,
                   "seeds": seeds, "folds": a.folds, "inner_folds": lib.INNER_FOLDS, "C_grid": lib.C_GRID,
                   "max_iter": lib.MAX_ITER, "pattern_sets": lib.PATTERN_SETS, "baselines": lib.BASELINES,
                   **settings},
        "departures_from_design": departures,
    }
    if a.kind == "measurement" and meta["tree"] != "clean":
        fail(log, "a measurement run needs a clean working tree (the commit must describe the code that ran)")

    env_extra = {}
    if a.voting:
        with open(a.voting) as f:
            vc = json.load(f)
        env_extra = {"WELT_VOTER": str(vc["voter"]), "WELT_WORDER": str(vc["worder"])}
        meta["voting_choice"] = {"file": os.path.basename(a.voting), "sha256": lib.sha256(a.voting), **env_extra}
    log("  WeLT voting: " + (json.dumps(env_extra) if env_extra else "runner default (WELT_VOTER unset)"))

    # 1. data
    log("== 1. data")
    ensure_raw(log)
    s_labels, s_edges, target, facts = build_struct_graph(log, out)
    meta["data_sha256"] = {rel(STRUCT_LG): lib.LASTFM_STRUCT_LG_SHA, rel(REF_LG): lib.LASTFM_LG_SHA,
                           **{"datasets/raw/" + m: s for m, s in lib.LASTFM_MEMBERS.items()}}
    meta["leakage_check"] = facts

    # 2. threshold selection (one warmup and one measured run per cell: only the counts are used)
    log("== 2. threshold selection")
    tw = grid_tau_w(log)
    log("  tau_w = min + rho_w * (max - min): " + json.dumps(tw))
    selection_ms = []
    fws_dumps = {}
    checks = {}

    def run_selection_cell(tau_s, tau_w):
        rho = next(r for r, t in tw.items() if t == tau_w)
        d = os.path.join(out, "selection_patterns", f"WeLT_{tau_s}_{rho}")
        row, dump, _ = run_bench(log, STRUCT_LG, "WeLT", tau_s, tau_w, d, a.selection_limit_ms, 1, 1, env_extra)
        fws_dumps[(tau_s, tau_w)] = dump
        n = int(row["R"])
        selection_ms.append({"miner": "WeLT", "tau_s": tau_s, "rho_w": rho, "status": row["status"],
                             "totalMs": row["totalMs"]})
        log(f"  WeLT  tau_s={tau_s:<5} rho_w={rho} tau_w={tau_w}: status={row['status']} |R|={n}")
        return row["status"], n

    def second_condition(tau_s, tau_w):
        k = (max(e for _c, _v, e, _w in lib.read_dump(fws_dumps[(tau_s, tau_w)]))
             if a.frequent_bound == "fws-edges" else 0)
        if (tau_s, k) not in checks:
            d = os.path.join(out, "selection_patterns", f"GraMi_{tau_s}_maxEdges{k}")
            row, _dump, _ = run_bench(log, STRUCT_LG, "GraMi", tau_s, tau_w, d, a.check_limit_ms, 1, 1, {}, k)
            selection_ms.append({"miner": "GraMi", "tau_s": tau_s, "max_edges": k, "status": row["status"],
                                 "totalMs": row["totalMs"]})
            checks[(tau_s, k)] = {"miner": "GraMi", "max_edges": k, "limit_ms": a.check_limit_ms,
                                  "status": row["status"], "patterns": int(row["R"])}
            log(f"  GraMi tau_s={tau_s:<5} max edges={k or 'any'}: status={row['status']} patterns={row['R']}")
        note = checks[(tau_s, k)]
        return note["status"] == "OK", note

    chosen, reason, grid_rows = lib.scan(lib.selection_order(tw), run_selection_cell, second_condition)
    skipped = sum(1 for r in grid_rows if r["status"] == "SKIPPED")
    if skipped:
        log(f"  {skipped} cells skipped: a higher tau_s at their rho_w is already above the window")
    choice = {"rule": (f"first (tau_s descending, rho_w in {list(lib.RHO_W)}) pair where WeLT returns "
                       f"{lib.PATTERN_WINDOW[0]} <= |R| <= {lib.PATTERN_WINDOW[1]} FWS and GraMi completes at "
                       f"the same tau_s within {a.check_limit_ms} ms "
                       + ("mining patterns of at most the largest FWS edge count" if a.frequent_bound == "fws-edges"
                          else "mining patterns of any size") + "; no class label is used"),
              "frequent_bound": a.frequent_bound,
              "grid": {"tau_s": lib.TAU_S_GRID, "rho_w": lib.RHO_W},
              "selection_runs": {"WeLT_limit_ms": a.selection_limit_ms, "GraMi_limit_ms": a.check_limit_ms,
                                 "warmup": 1, "measured": 1},
              "tau_w_of_rho_w": tw, "scanned": grid_rows, "reason": reason, "chosen": None}
    if chosen is None:
        write_json(os.path.join(out, "downstream_choice.json"), choice)
        fail(log, reason, code=2)
    c = grid_rows[chosen]
    max_edges = c["second_condition"]["max_edges"]
    choice["chosen"] = {"tau_s": c["tau_s"], "rho_w": c["rho_w"], "tau_w": c["tau_w"], "R": c["R"],
                        "frequent_max_edges": max_edges, "frequent_patterns": c["second_condition"]["patterns"]}
    write_json(os.path.join(out, "downstream_choice.json"), choice)
    log("  chosen: " + reason + f"; GraMi max edges {max_edges or 'any'}")
    tau_s, tau_w = c["tau_s"], c["tau_w"]

    # 3. mining at the chosen cell with the timing protocol
    log("== 3. mining at the chosen cell")
    heavy = os.path.join(out, "heavy_edges.lg")
    h = subprocess.run([sys.executable, os.path.join(ROOT, "tools", "heavy_subgraph.py"), STRUCT_LG, tau_w, heavy],
                       capture_output=True, text=True, cwd=ROOT)
    log("  " + (h.stdout + h.stderr).strip().replace(out, "<out>").replace(ROOT, "<repo>"))
    if h.returncode != 0:
        fail(log, "tools/heavy_subgraph.py failed")
    miners = (("FWS", STRUCT_LG, "WeLT", env_extra, 0, c["R"]),
              ("Frequent (all)", STRUCT_LG, "GraMi", {}, max_edges, c["second_condition"]["patterns"]),
              ("ALL", heavy, "GraMi", {}, 0, None))
    mined, mining_rows, mining_time, commands = {}, [], {}, {}
    for name, graph, algo, env, k, expected in miners:
        d = os.path.join(out, "mined", re.sub(r"[^A-Za-z]+", "_", name).strip("_"))
        row, dump, cmd = run_bench(log, graph, algo, tau_s, tau_w, d, a.limit_ms, a.warmup, a.measured, env, k)
        if row["status"] != "OK" or dump is None or (expected is not None and int(row["R"]) != expected):
            fail(log, f"the timed {name} run ended with status {row['status']}, {row['R']} patterns "
                      f"(selection: {expected})", code=2)
        mined[name] = dump
        mining_rows.append({"set": name, **counts_only(row)})
        mining_time[name] = {k2: row[k2] for k2 in ("runs", "warmup", *[x for x in row if is_timing_column(x)])}
        commands[name] = cmd
        log(f"  {name:<15} {algo:<6} patterns={row['R']:<6} totalMs median={row['totalMs']} over {row['runs']} runs")
    write_csv(os.path.join(out, "mining.csv"), mining_rows)

    codes = {name: [r[0] for r in lib.read_dump(p)] for name, p in mined.items()}
    nesting = {
        "FWS_in_frequent": sum(1 for x in codes["FWS"] if x in set(codes["Frequent (all)"])),
        "ALL_in_FWS": sum(1 for x in codes["ALL"] if x in set(codes["FWS"])),
        "FWS": len(codes["FWS"]), "ALL": len(codes["ALL"]), "frequent": len(codes["Frequent (all)"]),
    }
    log("  nesting: " + json.dumps(nesting))
    if nesting["FWS_in_frequent"] != nesting["FWS"] or nesting["ALL_in_FWS"] != nesting["ALL"]:
        fail(log, "the result sets are not nested (ALL within FWS within the frequent patterns)")

    # 4. image sets
    log("== 4. image sets")
    img_dir = os.path.join(out, "image_sets")
    os.makedirs(img_dir, exist_ok=True)
    images, image_ms = {}, {}
    images["FWS"], image_ms["FWS"] = image_sets(log, mined["FWS"], tau_w, "full", os.path.join(img_dir, "FWS.tsv"))
    freq_rows, image_ms["Frequent (all)"] = image_sets(log, mined["Frequent (all)"], tau_w, "full",
                                                       os.path.join(img_dir, "Frequent_all.tsv"))
    images["Frequent (all)"] = freq_rows
    mni = lib.mni_of(freq_rows)
    top = lib.top_by_mni(codes["Frequent (all)"], mni, len(codes["FWS"]))
    keep = set(top)
    images["Frequent (same count)"] = [r for r in freq_rows if r[0] in keep]
    image_ms["Frequent (same count)"] = image_ms["Frequent (all)"]
    with open(os.path.join(img_dir, "Frequent_same_count.selected.tsv"), "w", newline="\n") as f:
        f.write("rank\tpattern\tcode\tmni\n")
        for rank, i in enumerate(top):
            f.write(f"{rank}\t{i}\t{codes['Frequent (all)'][i]}\t{mni[i]}\n")
    images["ALL (images in G)"], image_ms["ALL (images in G)"] = image_sets(
        log, mined["ALL"], tau_w, "full", os.path.join(img_dir, "ALL_G.tsv"))
    images["ALL (images in heavy edges)"], image_ms["ALL (images in heavy edges)"] = image_sets(
        log, mined["ALL"], tau_w, "filtered", os.path.join(img_dir, "ALL_heavy_edges.tsv"))
    patterns_dir = os.path.join(out, "patterns")
    os.makedirs(patterns_dir, exist_ok=True)
    for name, p in mined.items():
        shutil.copyfile(p, os.path.join(patterns_dir, re.sub(r"[^A-Za-z]+", "_", name).strip("_") + ".tsv"))

    # 5. features and classification
    log("== 5. features and classification")
    import numpy as np
    from scipy import sparse
    n = len(s_labels)
    t0 = time.perf_counter()
    base = lib.baselines(n, s_edges, os.path.join(LASTFM_RAW, "lastfm_asia_features.json"))
    pattern_mats, dims = {}, {"B0": base["B0"].shape[1], "B1": base["B1"].shape[1]}
    for name, _graph in lib.PATTERN_SETS:
        rows_ = images[name]
        pats = len({p for p, _u, _i in rows_})
        if not rows_:
            if not name.startswith("ALL"):
                fail(log, f"the {name} pattern set gives no feature column", code=2)
            pattern_mats[name] = None
            dims[name] = {"patterns": 0, "columns_before_dedupe": 0, "columns_after_dedupe": 0, "empty": True}
            continue
        m, before, after = lib.dedupe_columns(lib.pattern_matrix(n, rows_))
        pattern_mats[name] = m
        dims[name] = {"patterns": pats, "columns_before_dedupe": before, "columns_after_dedupe": after,
                      "nonzeros": int(m.nnz),
                      "vertices_with_a_feature": int(np.sum(np.asarray(m.sum(axis=1)).ravel() > 0)),
                      "empty": False}
    matrices = {}
    for b in lib.BASELINES:
        matrices[b] = base[b]
    for b in lib.BASELINES:
        for name, _graph in lib.PATTERN_SETS:
            m = pattern_mats[name]
            matrices[f"{b} + {name}"] = None if m is None else sparse.hstack([base[b], m]).tocsr()
    if list(matrices) != lib.config_names():
        fail(log, "the configurations differ from lib.config_names()")
    assemble_ms = (time.perf_counter() - t0) * 1000
    for name, d in dims.items():
        log(f"  {name}: {json.dumps(d)}")
    y = np.asarray(target)
    t0 = time.perf_counter()
    rows = lib.evaluate(matrices, y, seeds=seeds, folds=a.folds, log=log, n_jobs=a.jobs)
    classify_ms = (time.perf_counter() - t0) * 1000
    write_csv(os.path.join(out, "folds.csv"), rows)
    summary = lib.summarise(rows)
    summary["dimensions"] = dims
    summary["classes"] = {"count": int(len(set(target))), "smallest": int(min(np.bincount(y))),
                          "largest": int(max(np.bincount(y)))}
    summary["chosen_cell"] = choice["chosen"]
    summary["nesting"] = nesting
    write_json(os.path.join(out, "summary.json"), summary)
    write_csv(os.path.join(out, "summary_configs.csv"),
              [{"features": k, **{x: y_ for x, y_ in v.items() if x != "C_chosen"},
                **{f"C={c_}": v.get("C_chosen", {}).get(c_, "") for c_ in map(str, lib.C_GRID)}}
               for k, v in summary["per_config"].items()])
    write_csv(os.path.join(out, "summary_tests.csv"), summary["tests"])
    for name, s in summary["per_config"].items():
        if s["empty"]:
            log(f"  {name:<34} empty pattern set")
            continue
        log(f"  {name:<34} macro-F1 {s['macro_f1_mean']:.4f} ({s['macro_f1_sd']:.4f})   "
            f"accuracy {s['accuracy_mean']:.4f} ({s['accuracy_sd']:.4f})   not converged {s['not_converged']}/{s['folds']}")
    for t in summary["tests"]:
        if t.get("empty"):
            continue
        log(f"  {t['reference']} vs {t['other']} [{t['metric']}]: mean diff {t['mean_difference']:+.4f}, "
            f"t={t['t']:.3f}, df={t['df']}, p={t['p_value']:.4g}")

    # 6. timings and run record
    timings = {
        "kind": a.kind,
        "note": ("feasibility probe: timings from a development machine, not a measurement"
                 if a.kind == "probe" else "measurement-machine run (revision.sh downstream)"),
        "host": host,
        "mining": mining_time,
        "mining_commands": commands,
        "selection_runs": selection_ms,
        "image_set_ms": image_ms,
        "matrix_assembly_ms": round(assemble_ms, 1),
        "classification_wall_ms": round(classify_ms, 1),
        "feature_dimension": dims,
    }
    write_json(os.path.join(out, f"timings_{a.kind}.json"), timings)
    meta["finished"] = datetime.datetime.now().isoformat(timespec="seconds")
    meta["outputs"] = sorted(rel(os.path.join(dp, fn)).replace(rel(out) + "/", "")
                             for dp, _dn, fns in os.walk(out) for fn in fns)
    write_json(os.path.join(out, "run_meta.json"), meta)
    log(f"== done -> {rel(out)}")


if __name__ == "__main__":
    main()
