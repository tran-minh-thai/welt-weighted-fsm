#!/usr/bin/env python3
"""Export the numbers of the round-5 campaign (revision.sh r5sweep, r5scal, r5grami) as one JSON data file.

    python tools/export_round5_numbers.py <campaign dir> <out.json>
    python tools/export_round5_numbers.py --self-test <campaign dir>

Writes every value the paper may cite, without formatting or labels, each with its source file (path
relative to the checkout that holds it, and SHA-256). Sections:
  e3   the shared-table sweep against one WeLT run per tau_w (R5_sweep_*): per dataset the sum of the
       ten separate medians and its spread (square root of the summed variances), the sweep total and
       its spread, their ratio and the verdict of the tie rule (summarise_campaign.verdict on the two
       totals), the points whose R and M agree; the search counters of the sweep cover the table and
       the mining together, so no search-counter direction is exported for this comparison;
  r5s  the scalability group at a high tau_w (R5_scal_high_tau_w.csv): per cell and algorithm status,
       time, R, N, M, the MNI-frequent patterns seen (at the stop when the run reached the limit), and
       the smallest ratio of GraMiFilter's limit to WeLT's time where only WeLT completes;
  val  the public GraMi against GraMiStrategy (R5_grami_*): per cell status, pattern count, GraMi's own
       time (from before reading the graph file to the end of the search) and its spread, the time of
       GraMiStrategy (graph reading excluded), and the pattern-set comparison.
Exits 1, naming each failed condition, when the campaign may not be cited: a JDK lock other than the
r2 lock (results/v2_revision/r2_environment.lock), a run record on a modified tree, a row whose counters
differ across its runs, a pattern comparison that failed, or a missing file. --self-test copies the
campaign directory, injects one fault per condition and requires each to be caught.
"""
import csv
import glob
import hashlib
import json
import math
import os
import shutil
import statistics
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
_argv, sys.argv = sys.argv, sys.argv[:1]     # summarise_campaign reads sys.argv at import
import summarise_campaign as sc  # noqa: E402
sys.argv = _argv

LOCK = os.path.join(ROOT, "results", "v2_revision", "r2_environment.lock")
FILES = ("environment.lock", "R5_sweep_separate.csv", "R5_sweep_shared.csv", "R5_scal_high_tau_w.csv",
         "R5_grami_public.csv", "R5_grami_ours.csv", "R5_grami_compare.txt", "R5_grami_build_info.json")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


class Out:
    def __init__(self, camp):
        self.camp, self.values, self.sources, self.files, self.problems = camp, {}, {}, {}, []
        top = os.path.abspath(camp)
        while not os.path.exists(os.path.join(top, ".git")) and os.path.dirname(top) != top:
            top = os.path.dirname(top)
        self.top = top

    def src(self, name):
        path = os.path.join(self.camp, name)
        r = os.path.relpath(os.path.abspath(path), self.top).replace(os.sep, "/")
        self.files[r] = sha256(path)
        return r

    def put(self, key, value, name):
        if key in self.values:
            raise ValueError(f"key {key} written twice")
        self.values[key] = value
        self.sources[key] = self.src(name)

    def need(self, ok, msg):
        if not ok:
            self.problems.append(msg)

    def rows(self, name):
        with open(os.path.join(self.camp, name), newline="", encoding="utf-8") as fh:
            return list(csv.DictReader(fh))


def ds(r):
    return r["dataset"][:-3] if r["dataset"].endswith(".lg") else r["dataset"]


def tau_key(t):
    """A tau_w as a key part: 999 -> w999, 47.8261 -> w47_8261."""
    s = f"{float(t):.4f}".rstrip("0").rstrip(".")
    return "w" + s.replace(".", "_")


def protocol(o):
    lock = open(os.path.join(o.camp, "environment.lock"), encoding="utf-8").read().replace("\r", "")
    ref = open(LOCK, encoding="utf-8").read().replace("\r", "")
    o.need(lock == ref, "environment.lock differs from the r2 lock")
    o.put("r5.protocol.jdk_lock_equals_r2", int(lock == ref), "environment.lock")
    metas = sorted(glob.glob(os.path.join(o.camp, "meta_*.json")))
    o.need(bool(metas), "no run record meta_*.json")
    steps = 0
    for p in metas:
        m = json.load(open(p, encoding="utf-8"))
        n = os.path.basename(p)
        if not m.get("endTime"):
            continue          # an attempt stopped before its first measurement (no end recorded)
        steps += 1
        o.need(m.get("gitTree") == "clean", f"{n}: gitTree {m.get('gitTree')!r}")
        o.need(f"java.runtime.version = {m.get('javaRuntimeVersion')}" in lock and f"java.vendor = {m.get('javaVendor')}" in lock
               and f"JAVA_OPTS = {m.get('JAVA_OPTS')}" in lock, f"{n}: JDK or JAVA_OPTS differs from environment.lock")
    o.put("r5.protocol.run_records", steps, "environment.lock")
    o.put("r5.protocol.src_tree", open(os.path.join(o.camp, "src.lock"), encoding="utf-8").read().strip(), "src.lock")


def deterministic(o, name, rows):
    for r in rows:
        if r.get("status") == "OK" and "deterministic" in r:
            o.need(r["deterministic"] == "1", f"{name}: {ds(r)} {r['minSup']} {r.get('algorithm')}: counters differ across runs")


def e3(o):
    sep, sh = o.rows("R5_sweep_separate.csv"), o.rows("R5_sweep_shared.csv")
    deterministic(o, "R5_sweep_separate.csv", sep)
    pts = {(ds(p), round(float(p["minWeight"]), 3)): p for p in sh if p["row"] == "point"}
    faster = 0
    for sw in (r for r in sh if r["row"] == "sweep"):
        d = ds(sw)
        rows = [r for r in sep if ds(r) == d and r["status"] == "OK"]
        o.need(len(rows) == len([p for p in pts if p[0] == d]) and rows, f"R5_sweep_separate.csv: {d}: separate runs and sweep points differ")
        tot = sum(float(r["totalMs"]) for r in rows)
        sd = math.sqrt(sum(float(r["totalSdMs"]) ** 2 for r in rows))
        a = {"totalMs": tot, "totalSdMs": sd}
        b = {"totalMs": float(sw["totalMs"]), "totalSdMs": float(sw["totalSdMs"])}
        v = sc.verdict(b, a)
        faster += v == "faster"
        equal = sum(1 for r in rows if (p := pts.get((d, round(float(r["minWeight"]), 3)))) and p["R"] == r["R"] and p["M"] == r["M"])
        base = f"e3.{d}"
        o.put(f"{base}.separate.runs", len(rows), "R5_sweep_separate.csv")
        o.put(f"{base}.separate.total_ms", tot, "R5_sweep_separate.csv")
        o.put(f"{base}.separate.total_sd_ms", sd, "R5_sweep_separate.csv")
        for k, col in (("total_ms", "totalMs"), ("total_sd_ms", "totalSdMs"), ("total_min_ms", "totalMinMs"),
                       ("total_max_ms", "totalMaxMs"), ("table_ms", "tableBuildMs"), ("query_ms", "queryMs")):
            o.put(f"{base}.shared.r5.{k}", float(sw[col]), "R5_sweep_shared.csv")
        o.put(f"{base}.shared.r5.ratio", tot / b["totalMs"], "R5_sweep_shared.csv")
        o.put(f"{base}.shared.r5.verdict", v, "R5_sweep_shared.csv")
        o.put(f"{base}.shared.r5.points_equal_R_M", equal, "R5_sweep_shared.csv")
        o.need(equal == len(rows), f"R5_sweep_shared.csv: {d}: R or M of the sweep differs from the separate runs at {len(rows) - equal} points")
    o.put("e3.shared.count.faster", faster, "R5_sweep_shared.csv")
    o.put("e3.shared.counter_condition", "not recorded", "R5_sweep_shared.csv")


def r5s(o):
    rows = o.rows("R5_scal_high_tau_w.csv")
    deterministic(o, "R5_scal_high_tau_w.csv", rows)
    cells = {}
    for r in rows:
        cells.setdefault((ds(r), int(r["minSup"]), r["minWeight"]), {})[r["algorithm"]] = r
    only = 0
    dumps = os.listdir(os.path.join(o.camp, "patterns")) if os.path.isdir(os.path.join(o.camp, "patterns")) else []
    for (d, s, tw), al in sorted(cells.items()):
        # the CSV writes tau_w with three decimals; the exact value is in the name of the cell's result-set file
        exact = sorted({f[:-4].rsplit("_", 1)[1] for f in dumps if f.startswith(f"{d}_WeLT_{s}_") and f.endswith(".tsv")
                        and round(float(f[:-4].rsplit("_", 1)[1]), 3) == round(float(tw), 3)})
        o.need(len(exact) == 1, f"patterns/: {d} {s}: {len(exact)} result-set files for tau_w {tw} (one expected)")
        if len(exact) != 1:
            continue
        tw = exact[0]
        base = f"r5s.{d}.{s}.{tau_key(tw)}"
        o.put(base + ".tau_w", float(tw), "R5_scal_high_tau_w.csv")
        for a, r in al.items():
            o.put(f"{base}.{a}.status", r["status"], "R5_scal_high_tau_w.csv")
            for k in ("R", "N", "M"):
                o.put(f"{base}.{a}.{k}", int(r[k]), "R5_scal_high_tau_w.csv")
            o.put(f"{base}.{a}.frequent_seen", int(r["frequentSize"]), "R5_scal_high_tau_w.csv")
            if r["status"] == "OK":
                o.put(f"{base}.{a}.ms", float(r["totalMs"]), "R5_scal_high_tau_w.csv")
                o.put(f"{base}.{a}.sd_ms", float(r["totalSdMs"]), "R5_scal_high_tau_w.csv")
        w, g = al.get("WeLT"), al.get("GraMiFilter")
        if w and g and w["status"] == "OK" and g["status"] == "TO":
            only += 1
            o.put(base + ".filter_limit_over_welt", float(g["limitMs"]) / float(w["totalMs"]), "R5_scal_high_tau_w.csv")
    # the frequent patterns seen by GraMiFilter bound |Q| from below at each support (its search does not depend on tau_w)
    for (d, s) in sorted({(c[0], c[1]) for c in cells}):
        seen = [int(al["GraMiFilter"]["frequentSize"]) for c, al in cells.items() if c[:2] == (d, s) and "GraMiFilter" in al]
        if seen:
            o.put(f"r5s.{d}.{s}.Q_at_least", max(seen), "R5_scal_high_tau_w.csv")
    o.put("r5s.count.cells", len(cells), "R5_scal_high_tau_w.csv")
    o.put("r5s.count.only_welt", only, "R5_scal_high_tau_w.csv")


def val(o):
    info = json.load(open(os.path.join(o.camp, "R5_grami_build_info.json"), encoding="utf-8"))
    o.put("val.grami_public.commit", info["commit"][:7], "R5_grami_build_info.json")
    o.put("val.grami_public.removed_lines", len(info["removed_lines"]), "R5_grami_build_info.json")
    pub, ours = o.rows("R5_grami_public.csv"), o.rows("R5_grami_ours.csv")
    deterministic(o, "R5_grami_ours.csv", ours)
    for r in pub:
        base = f"val.grami_public.{r['dataset']}.{r['minSup']}"
        o.put(base + ".status", r["status"], "R5_grami_public.csv")
        if r["status"] == "OK":
            o.put(base + ".Q", int(r["patterns"]), "R5_grami_public.csv")
            o.put(base + ".sec", float(r["graMiSec"]), "R5_grami_public.csv")
            o.put(base + ".sd_sec", float(r["graMiSdSec"]), "R5_grami_public.csv")
    for r in ours:
        base = f"val.grami_ours.{ds(r)}.{r['minSup']}"
        o.put(base + ".status", r["status"], "R5_grami_ours.csv")
        if r["status"] == "OK":
            o.put(base + ".Q", int(r["R"]), "R5_grami_ours.csv")
            o.put(base + ".ms", float(r["totalMs"]), "R5_grami_ours.csv")
            o.put(base + ".sd_ms", float(r["totalSdMs"]), "R5_grami_ours.csv")
    lines = [x.split() for x in open(os.path.join(o.camp, "R5_grami_compare.txt"), encoding="utf-8").read().splitlines() if x.strip()]
    match = sum(1 for x in lines if "exit=0" in x)
    o.need(lines and match == len(lines), f"R5_grami_compare.txt: {len(lines) - match} of {len(lines)} comparisons failed")
    o.put("val.grami_public.count.compared", len(lines), "R5_grami_compare.txt")
    o.put("val.grami_public.count.match", match, "R5_grami_compare.txt")
    o.put("val.grami_public.count.to", sum(1 for r in pub if r["status"] == "TO"), "R5_grami_public.csv")
    o.put("val.grami_public.to_cells", "; ".join(f"{r['dataset']} {r['minSup']}" for r in pub if r["status"] == "TO"),
          "R5_grami_public.csv")


def export(camp, out_path):
    missing = [f for f in FILES if not os.path.isfile(os.path.join(camp, f))]
    if missing:
        return [f"{f}: missing" for f in missing], None
    o = Out(camp)
    for f in (protocol, e3, r5s, val):
        f(o)
    if o.problems:
        return o.problems, None
    if out_path:
        with open(out_path, "w", encoding="utf-8", newline="\n") as fh:
            json.dump({"values": o.values, "sources": o.sources, "files": o.files}, fh, indent=2, sort_keys=True)
    return [], o


def self_test(camp):
    """One fault per refusal, each in a fresh copy of the campaign directory; the unchanged copy must pass."""
    def edit(path, old, new):
        s = open(path, encoding="utf-8").read()
        assert old in s, f"self-test: {old!r} not in {path}"
        open(path, "w", encoding="utf-8").write(s.replace(old, new, 1))
    def set_field(path, field, value):
        with open(path, newline="", encoding="utf-8") as fh:
            rows = list(csv.DictReader(fh))
        assert rows and field in rows[0], f"self-test: no {field} in {path}"
        rows[0][field] = value
        with open(path, "w", newline="", encoding="utf-8") as fh:
            w = csv.DictWriter(fh, fieldnames=list(rows[0]))
            w.writeheader()
            w.writerows(rows)
    meta = sorted(f for f in os.listdir(camp) if f.startswith("meta_") and "r5sweep" in f)[0]
    faults = [
        ("the unchanged copy is exported", None),
        ("a JDK lock other than r2", lambda d: edit(os.path.join(d, "environment.lock"), "Microsoft", "Oracle")),
        ("a run record on a modified tree", lambda d: edit(os.path.join(d, meta), '"clean"', '"MODIFIED (1 files)"')),
        ("counters differ across runs", lambda d: set_field(os.path.join(d, "R5_sweep_separate.csv"), "deterministic", "0")),
        ("a failed pattern comparison", lambda d: edit(os.path.join(d, "R5_grami_compare.txt"), "exit=0", "exit=1")),
        ("a missing file", lambda d: os.remove(os.path.join(d, "R5_grami_public.csv"))),
    ]
    ok = 0
    for name, fault in faults:
        with tempfile.TemporaryDirectory() as t:
            d = os.path.join(t, "camp")
            shutil.copytree(camp, d)
            if fault:
                fault(d)
            problems, _o = export(d, None)
        caught = (not problems) if fault is None else bool(problems)
        ok += caught
        print(f"{'ok   ' if caught else 'FAIL '} {name}: {'; '.join(problems)[:110] if problems else 'exported'}")
    print(f"export_round5_numbers self-test: {ok}/{len(faults)}")
    return ok == len(faults)


def main():
    if len(sys.argv) == 3 and sys.argv[1] == "--self-test":
        sys.exit(0 if self_test(sys.argv[2]) else 1)
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    problems, o = export(*sys.argv[1:])
    if problems:
        for p in problems:
            print("ERROR: " + p, file=sys.stderr)
        sys.exit(1)
    print(f"export_round5_numbers: {len(o.values)} values from {len(o.files)} files -> {sys.argv[2]}")


if __name__ == "__main__":
    main()
