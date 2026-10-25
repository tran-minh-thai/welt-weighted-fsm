#!/usr/bin/env python3
"""Export the numbers of a revision campaign as one JSON data file (standard library only).

    python tools/export_campaign_numbers.py <campaign dir> <out.json>

Reads the CSV files and run records written by revision.sh (the files that
tools/summarise_campaign.py summarises) and writes every value the paper may cite, without
formatting or labels, each with its source file (path relative to the checkout that holds it, and
SHA-256). Sections:
  protocol  machine, JDK, heap, warmup and measured runs, time limit, tie rule, default voter;
  e1        efficiency (b1, b1low): per cell and algorithm; WeLT against GraMiFilter by the win rule;
            the gating invariant; result-set equality of WeLT and GraMiFilter; average pattern size;
            the cells where neither WeLT, GraMiFilter nor GraMi completes;
  e2        ablation (b4, b4hard, b4low) against full by the tie rule, and the three-JVM passes
            (b4rep) by the rule of summarise_campaign.rep_verdict;
  e3        tau_w sweep (b5): GraMiFilter, WEGM, WeLT, the shared table, the exact ALL set, WeLT only;
  e4        peak live heap and retained data (b3live);
  e5        voting-weight sensitivity (b2grid, b2hard);
  e6        WEGM, the exact ALL set and WeLT at WEGM's supports, and the nesting checks (b7).
Win rule (the protocol of the paper): a time difference counts only when it exceeds the tie rule of
summarise_campaign.verdict and at least one search counter (candidates that reach MNICount, or
embedding searches) differs in the same direction; otherwise the cell is a tie.
Exits 1, naming each failed condition, when the campaign is not one the paper may cite: a run
record on a modified tree or another JDK, a measurement row whose counters differ across its runs,
a missing file, or a result-set check that failed.
"""
import collections
import csv
import glob
import hashlib
import json
import math
import os
import re
import statistics
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
_argv, sys.argv = sys.argv, sys.argv[:1]     # summarise_campaign reads sys.argv at import
import summarise_campaign as sc  # noqa: E402
sys.argv = _argv

TIE_FACTOR = 3   # the factor of summarise_campaign.verdict


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
        path = os.path.join(self.camp, name)
        if not os.path.exists(path):
            self.problems.append(f"{name}: missing")
            return []
        with open(path, newline="", encoding="utf-8") as fh:
            return list(csv.DictReader(fh))


def ds(r):
    return r["dataset"][:-3] if r["dataset"].endswith(".lg") else r["dataset"]


def num(v):
    """An integer or float from a CSV field; None when empty."""
    if v is None or v == "":
        return None
    f = float(v)
    return int(f) if f.is_integer() and "." not in v else f


def searches(r):
    return int(r["mniIsoCallsMine"]) + int(r["weightIsoCalls"])


def win(a, b):
    """'faster', 'slower' or 'tie' for a against b by the win rule (tie rule and a counter in the same direction)."""
    t = sc.verdict(a, b)
    if t == "tie":
        return "tie"
    sign = -1 if t == "faster" else 1
    counters = [(int(a["M"]) - int(b["M"])), searches(a) - searches(b)]
    return t if any(c * sign > 0 for c in counters) else "tie"


def protocol(o):
    lock = open(os.path.join(o.camp, "environment.lock"), encoding="utf-8").read()
    o.src("environment.lock")
    metas = sorted(glob.glob(os.path.join(o.camp, "meta_*.json")))
    o.need(bool(metas), "no run record meta_*.json")
    for p in metas:
        m = json.load(open(p, encoding="utf-8"))
        n = os.path.basename(p)
        o.need(m.get("gitTree") == "clean", f"{n}: gitTree {m.get('gitTree')!r}")
        o.need(f"java.runtime.version = {m.get('javaRuntimeVersion')}" in lock and
               f"java.vendor = {m.get('javaVendor')}" in lock and f"JAVA_OPTS = {m.get('JAVA_OPTS')}" in lock,
               f"{n}: JDK or JAVA_OPTS differs from environment.lock")
    first = os.path.basename(metas[0])
    m = json.load(open(metas[0], encoding="utf-8"))
    heap = re.search(r"-Xmx(\d+)([gGmM])", m["JAVA_OPTS"])
    o.put("protocol.heap_gb", int(heap.group(1)) if heap.group(2) in "gG" else int(heap.group(1)) / 1024, first)
    o.put("protocol.cpu", m["cpu"].replace("(R)", "").replace("(TM)", "").replace(" CPU", "").strip(), first)
    o.put("protocol.cores", int(m["physicalCores"]), first)
    o.put("protocol.threads", int(m["logicalProcessors"]), first)
    o.put("protocol.ram_gb", float(m["ramGB"]), first)
    o.put("protocol.os", m["os"].replace(" 10.0", ""), first)
    o.put("protocol.jdk_vendor", m["javaVendor"], first)
    o.put("protocol.jdk_version", m["javaRuntimeVersion"].split("+")[0], first)
    o.put("protocol.warmup", int(m["warmup"]), first)
    o.put("protocol.measured", int(m["measured"]), first)
    o.put("protocol.limit_min", int(m["limitMs"]) / 60000, first)
    o.put("protocol.tie_factor", TIE_FACTOR, first)
    o.put("protocol.run_records", len(metas), first)
    vc = json.load(open(os.path.join(o.camp, "voting_choice.json"), encoding="utf-8"))
    o.put("protocol.voter", vc["voter"].replace(",", ", "), "voting_choice.json")
    o.put("protocol.src_tree", open(os.path.join(o.camp, "src.lock"), encoding="utf-8").read().strip(), "src.lock")


CELL_FIELDS = ("status", "R", "N", "M", "Mprime", "extended", "frequentSize", "runs", "mniIsoCallsMine",
               "weightIsoCalls", "backtrack")


def e1(o):
    d = collections.defaultdict(dict)
    for name in ("B1_efficiency.csv", "B1_low.csv"):
        for r in o.rows(name):
            r["_file"] = name
            d[(ds(r), int(r["minSup"]))][r["algorithm"]] = r
    sizes = {}
    for r in o.rows("B7_equivalence.csv"):
        sizes[(ds(r), int(r["minSup"]))] = r
    eq = o.rows("B7_equivalence.csv")
    o.put("e1.equal_sets.compared", len(eq), "B7_equivalence.csv")
    o.put("e1.equal_sets.equal", sum(1 for r in eq if r["sameSet"] == "1"), "B7_equivalence.csv")
    o.need(all(r["sameSet"] == "1" for r in eq) and eq, "B7_equivalence.csv: WeLT and GraMiFilter differ, or no cell")
    count, ratios, held, checked = collections.Counter(), [], 0, 0
    for (dname, s), algos in sorted(d.items()):
        base = f"e1.cell.{dname}.{s}"
        for a, r in algos.items():
            f = r["_file"]
            if r["status"] == "OK":
                o.need(r["deterministic"] == "1", f"{f}: {dname} {s} {a}: counters differ across runs")
            for k in CELL_FIELDS:
                o.put(f"{base}.{a}.{k}", num(r[k]) if k != "status" else r[k], f)
            if r["status"] == "OK":
                o.put(f"{base}.{a}.ms", float(r["totalMs"]), f)
                o.put(f"{base}.{a}.sd_ms", float(r["totalSdMs"]), f)
        w, g = algos.get("WeLT"), algos.get("GraMiFilter")
        if w and w["status"] == "OK":
            checked += 1
            held += int(w["extended"]) == int(w["R"])
        if sc.ok(w) and sc.ok(g):
            v = win(w, g)
            ratios.append(float(g["totalMs"]) / float(w["totalMs"]))
            o.put(f"{base}.welt_vs_filter", v, w["_file"])
            o.put(f"{base}.filter_over_welt", ratios[-1], w["_file"])
            count[v] += 1
        elif sc.ok(w):
            count["only WeLT completes" if g else "GraMiFilter not run"] += 1
        else:
            count["WeLT does not complete"] += 1
        sz = sizes.get((dname, s))
        if sz is not None and sz["avgEdges_B"]:
            o.put(f"{base}.WeLT.avg_edges", float(sz["avgEdges_B"]), "B7_equivalence.csv")
            o.put(f"{base}.WeLT.max_edges", int(sz["maxEdges_B"]), "B7_equivalence.csv")
    for k, n in (("faster", "welt_faster"), ("slower", "welt_slower"), ("tie", "tie"),
                 ("only WeLT completes", "only_welt"), ("GraMiFilter not run", "filter_not_run"),
                 ("WeLT does not complete", "welt_not_complete")):
        o.put(f"e1.count.{n}", count[k], "B1_efficiency.csv")
    o.put("e1.count.cells", sum(count.values()), "B1_efficiency.csv")
    if ratios:
        o.put("e1.filter_over_welt.min", min(ratios), "B1_efficiency.csv")
        o.put("e1.filter_over_welt.median", statistics.median(ratios), "B1_efficiency.csv")
        o.put("e1.filter_over_welt.max", max(ratios), "B1_efficiency.csv")
    o.put("e1.gating.checked", checked, "B1_efficiency.csv")
    o.put("e1.gating.held", held, "B1_efficiency.csv")
    o.need(held == checked and checked > 0, f"gating invariant held in {held} of {checked} cells")
    wegm = [r for c in d.values() for a, r in c.items() if a == "WEGM" and sc.ok(r)]
    o.put("e1.wegm.completed", len(wegm), "B1_efficiency.csv")
    o.put("e1.wegm.with_patterns", sum(int(r["R"]) > 0 for r in wegm), "B1_efficiency.csv")
    # cells where neither WeLT, GraMiFilter nor GraMi completes
    hard = [k for k, c in sorted(d.items()) if all(not sc.ok(c.get(a)) for a in ("WeLT", "GraMiFilter", "GraMi"))
            and all(a in c for a in ("WeLT", "GraMiFilter", "GraMi"))]
    o.put("e1.hard.count", len(hard), "B1_efficiency.csv")
    o.put("e1.hard.cells", "; ".join(f"{a} {b}" for a, b in hard), "B1_efficiency.csv")
    for dname, s in hard:
        c = d[(dname, s)]
        for a in ("WeLT", "GraMiFilter", "GraMi"):
            r = c[a]
            o.put(f"e1.hard.{dname}.{s}.{a}.fws_share_of_frequent",
                  int(r["R"]) / int(r["frequentSize"]) if int(r["frequentSize"]) else None, r["_file"])
        o.put(f"e1.hard.{dname}.{s}.WEGM.status", c["WEGM"]["status"], c["WEGM"]["_file"])
    o.put("e1.hard.wegm_completes", sum(1 for k in hard if sc.ok(d[k].get("WEGM"))), "B1_efficiency.csv")


VARIANTS = ("noP1", "noP2", "noP2single", "noTable", "noTableBuild", "noP3", "p3R", "noWext", "noBnb", "voteDeg",
            "noWorder", "noTableReuse", "noTableThreshold", "noTableOptions")


def e2(o):
    for name, tag in (("B4_ablation.csv", "b4"), ("B4_ablation_hard.csv", "b4hard"), ("B4_low.csv", "b4low")):
        cells = collections.defaultdict(dict)
        for r in o.rows(name):
            cells[(ds(r), int(r["minSup"]))][r["variant"]] = r
        bad = [(k, v) for k, c in cells.items() for v, r in c.items() if sc.ok(r) and r["invariant"] not in ("OK", "REF")]
        o.put(f"e2.{tag}.cells", len(cells), name)
        o.put(f"e2.{tag}.result_set_violations", len(bad), name)
        o.need(not bad, f"{name}: result-set violations {bad}")
        tally = collections.Counter()
        for (dname, s), c in sorted(cells.items()):
            f = c.get("full")
            base = f"e2.{tag}.{dname}.{s}"
            if f is not None:
                o.put(f"{base}.full.status", f["status"], name)
                if sc.ok(f):
                    for k in ("ms", "N", "M", "backtrack"):
                        o.put(f"{base}.full.{k}", float(f["totalMs"]) if k == "ms" else int(f[k]), name)
            for v in VARIANTS:
                r = c.get(v)
                if r is None:
                    continue
                o.put(f"{base}.{v}.status", r["status"], name)
                if not sc.ok(r) or not sc.ok(f):
                    tally[(v, r["status"] if not sc.ok(r) else "full not complete")] += 1
                    continue
                t = sc.verdict(r, f)
                o.put(f"{base}.{v}.ratio", float(r["totalMs"]) / float(f["totalMs"]), name)
                o.put(f"{base}.{v}.verdict", t, name)
                for k in ("N", "M", "backtrack"):
                    o.put(f"{base}.{v}.{k}", int(r[k]), name)
                tally[(v, t)] += 1
        for (v, t), n in sorted(tally.items()):
            o.put(f"e2.{tag}.count.{v}.{t.replace(' ', '_')}", n, name)
    passes = [o.rows(f"B4_rep_jvm{i}.csv") for i in (1, 2, 3)]
    by = [collections.defaultdict(dict) for _ in passes]
    for b, rows in zip(by, passes):
        for r in rows:
            b[(ds(r), int(r["minSup"]))][r["variant"]] = r
    o.put("e2.rep.passes", len(passes), "B4_rep_jvm1.csv")
    o.put("e2.rep.min_effect", sc.MIN_EFFECT, "B4_rep_jvm1.csv")
    for k in sorted(by[0]):
        o.need(all(sc.ok(b[k].get("full")) for b in by), f"B4_rep: full not complete in every pass at {k}")
        full = [float(b[k]["full"]["totalMs"]) for b in by]
        for v in by[0][k]:
            rs = [b[k].get(v) for b in by]
            if v == "full" or not all(sc.ok(r) for r in rs):
                continue
            t, ratios, margin = sc.rep_verdict(full, [float(r["totalMs"]) for r in rs])
            base = f"e2.rep.{k[0]}.{k[1]}.{v}"
            o.put(base + ".verdict", t, "B4_rep_jvm1.csv")
            o.put(base + ".ratio_min", min(ratios), "B4_rep_jvm1.csv")
            o.put(base + ".ratio_max", max(ratios), "B4_rep_jvm1.csv")
            o.put(base + ".margin", margin, "B4_rep_jvm1.csv")
    for i in (2, 3):
        o.src(f"B4_rep_jvm{i}.csv")


def e3(o):
    allx = {(r["dataset"].split("_heavy_")[0].replace(".lg", ""), int(r["minSup"]), round(float(r["minWeight"]), 3)): r
            for r in o.rows("B5_all_exact.csv")}
    c = collections.defaultdict(dict)
    for r in o.rows("B5_tau_sweep.csv"):
        c[(ds(r), int(r["minSup"]), round(float(r["minWeight"]), 3))][r["algorithm"]] = r
    shared = {(ds(r), int(r["minSup"]), round(float(r["minWeight"]), 3)): r
              for r in o.rows("B5_shared_table.csv") if r["row"] == "point"}
    datasets = sorted({k[0] for k in c})
    o.put("e3.datasets", len(datasets), "B5_tau_sweep.csv")
    count = collections.Counter()
    for dname in datasets:
        pts = sorted(k for k in c if k[0] == dname)
        o.put(f"e3.{dname}.min_sup", pts[0][1], "B5_tau_sweep.csv")
        o.put(f"e3.{dname}.points", len(pts), "B5_tau_sweep.csv")
        for i, k in enumerate(pts):
            base = f"e3.{dname}.p{i}"
            o.put(base + ".tau_w", k[2], "B5_tau_sweep.csv")
            for a in ("GraMiFilter", "WEGM", "WeLT"):
                r = c[k].get(a)
                o.put(f"{base}.{a}.status", r["status"] if r else None, "B5_tau_sweep.csv")
                if sc.ok(r):
                    o.put(f"{base}.{a}.ms", float(r["totalMs"]), "B5_tau_sweep.csv")
                    o.put(f"{base}.{a}.R", int(r["R"]), "B5_tau_sweep.csv")
            w, g = c[k].get("WeLT"), c[k].get("GraMiFilter")
            if sc.ok(w) and sc.ok(g):
                v = win(w, g)
                o.put(base + ".welt_vs_filter", v, "B5_tau_sweep.csv")
                o.put(base + ".filter_over_welt", float(g["totalMs"]) / float(w["totalMs"]), "B5_tau_sweep.csv")
                count[v] += 1
            sh = shared.get(k)
            if sh is not None and sh["status"] == "OK":
                o.put(base + ".shared.query_ms", float(sh["queryMs"]), "B5_shared_table.csv")
                o.put(base + ".shared.R", int(sh["R"]), "B5_shared_table.csv")
                o.need(sc.ok(w) and int(sh["R"]) == int(w["R"]),
                       f"B5_shared_table.csv: {k}: R {sh['R']} differs from WeLT {w['R'] if w else None}")
            ax = allx.get(k)
            if ax is not None and sc.ok(ax):
                o.put(base + ".ALL.R", int(ax["R"]), "B5_all_exact.csv")
    for v in ("faster", "slower", "tie"):
        o.put(f"e3.count.welt_{v}", count[v], "B5_tau_sweep.csv")
    for r in o.rows("B5_welt_only.csv"):
        base = f"e3.welt_only.{ds(r)}.{round(float(r['minWeight']), 3)}"
        o.put(base + ".min_sup", int(r["minSup"]), "B5_welt_only.csv")
        o.put(base + ".status", r["status"], "B5_welt_only.csv")
        o.put(base + ".R", int(r["R"]), "B5_welt_only.csv")
        if sc.ok(r):
            o.put(base + ".ms", float(r["totalMs"]), "B5_welt_only.csv")


def e4(o):
    for r in o.rows("B3_live_heap.csv"):
        base = f"e4.{ds(r)}.{r['minSup']}.{r['algorithm']}"
        o.put(base + ".status", r["status"], "B3_live_heap.csv")
        for k, f in (("live_peak_mb", "liveHeapPeakMB"), ("graph_mb", "liveHeapGraphMB"), ("samples", "liveHeapSamples"),
                     ("N", "N"), ("visited_code_chars", "visitedCodeChars"), ("memo_code_chars", "memoCodeChars"),
                     ("frontier_ids_peak", "frontierDomainIntsPeak"), ("table_patterns", "tableKeptPatterns"),
                     ("table_ids", "tableDomainInts")):
            o.put(f"{base}.{k}", num(r[f]), "B3_live_heap.csv")


def e5(o):
    for name in ("B2_voting_grid.csv", "B2_voting_grid_hard.csv"):
        c = collections.defaultdict(list)
        for r in o.rows(name):
            c[(ds(r), int(r["minSup"]))].append(r)
        for (dname, s), rs in sorted(c.items()):
            done = [r for r in rs if sc.ok(r)]
            bt = [int(r["backtrack"]) for r in done]
            t = [float(r["totalMs"]) for r in done]
            base = [int(r["backtrack"]) for r in done if r["voter"].replace(";", ",") in ("1,1,1", "1.0,1.0,1.0")]
            key = f"e5.{dname}.{s}"
            o.put(key + ".voters", len(rs), name)
            o.put(key + ".completed", len(done), name)
            o.need(len(base) == 1, f"{name}: {dname} {s}: {len(base)} rows for the default voter")
            o.put(key + ".backtrack_default", base[0] if base else None, name)
            o.put(key + ".backtrack_min", min(bt), name)
            o.put(key + ".backtrack_max", max(bt), name)
            o.put(key + ".backtrack_ratio", max(bt) / max(1, min(bt)), name)
            o.put(key + ".time_ratio", max(t) / min(t), name)


def e6(o):
    c = collections.defaultdict(dict)
    for r in o.rows("B6_wegm_range.csv"):
        c[(ds(r), int(r["minSup"]))][r["algorithm"]] = r
    for r in o.rows("B6_wegm_range_all.csv"):
        c[(r["dataset"].split("_heavy_")[0], int(r["minSup"]))]["ALL"] = r
    for (dname, s), algos in sorted(c.items()):
        for a in ("WEGM", "ALL", "WeLT"):
            r = algos.get(a)
            base = f"e6.{dname}.{s}.{a}"
            src = "B6_wegm_range_all.csv" if a == "ALL" else "B6_wegm_range.csv"
            o.put(base + ".status", r["status"] if r else None, src)
            if sc.ok(r):
                o.put(base + ".R", int(r["R"]), src)
                o.put(base + ".ms", float(r["totalMs"]), src)
    nest = o.rows("B7_nesting.csv")
    sk_cmp = sum(1 for r in nest if r["exit"].strip() == "0" and r["skeletonsNotInAll"] not in ("", "-1"))
    sk_bad = sum(1 for r in nest if r["skeletonsNotInAll"] not in ("", "-1", "0"))
    aw_cmp = sum(1 for r in nest if r["exit"].strip() == "0" and r["allNotInWelt"] not in ("", "-1"))
    aw_bad = sum(1 for r in nest if r["allNotInWelt"] not in ("", "-1", "0"))
    o.put("e6.nesting.cells", len(nest), "B7_nesting.csv")
    o.put("e6.nesting.skeleton_in_all.compared", sk_cmp, "B7_nesting.csv")
    o.put("e6.nesting.skeleton_in_all.violations", sk_bad, "B7_nesting.csv")
    o.put("e6.nesting.all_in_welt.compared", aw_cmp, "B7_nesting.csv")
    o.put("e6.nesting.all_in_welt.violations", aw_bad, "B7_nesting.csv")
    o.put("e6.nesting.not_compared", sum(1 for r in nest if r["exit"].strip() == "2"), "B7_nesting.csv")
    o.need(sk_cmp > 0 and aw_cmp > 0 and sk_bad == 0 and aw_bad == 0, "B7_nesting.csv: no comparison or a violation")
    aw = o.rows("B7_all_within_welt.csv")
    o.put("e6.all_within_welt.compared", len(aw), "B7_all_within_welt.csv")
    o.put("e6.all_within_welt.violations", sum(1 for r in aw if r["onlyA"] != "0"), "B7_all_within_welt.csv")
    o.put("e6.all_within_welt.equal", sum(1 for r in aw if r["sameSet"] == "1"), "B7_all_within_welt.csv")
    o.need(aw and all(r["onlyA"] == "0" for r in aw), "B7_all_within_welt.csv: an ALL pattern outside WeLT, or no cell")
    ad = o.rows("B7_all_exact_definition.csv")
    o.put("e6.all_definition.compared", len(ad), "B7_all_exact_definition.csv")
    o.put("e6.all_definition.equal", sum(1 for r in ad if r["sameSet"] == "1"), "B7_all_exact_definition.csv")
    o.need(ad and all(r["sameSet"] == "1" for r in ad), "B7_all_exact_definition.csv: exact ALL differs from its definition")


REQUIRED = ("environment.lock", "src.lock", "voting_choice.json", "B1_efficiency.csv", "B1_low.csv", "B7_equivalence.csv",
            "B4_ablation.csv", "B4_ablation_hard.csv", "B4_low.csv", "B4_rep_jvm1.csv", "B4_rep_jvm2.csv",
            "B4_rep_jvm3.csv", "B5_tau_sweep.csv", "B5_shared_table.csv", "B5_all_exact.csv", "B5_welt_only.csv",
            "B3_live_heap.csv", "B2_voting_grid.csv", "B2_voting_grid_hard.csv", "B6_wegm_range.csv",
            "B6_wegm_range_all.csv", "B7_nesting.csv", "B7_all_within_welt.csv", "B7_all_exact_definition.csv")


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    camp, out_path = sys.argv[1:]
    missing = [f for f in REQUIRED if not os.path.isfile(os.path.join(camp, f))]
    if missing:
        for f in missing:
            print(f"ERROR: {f}: missing", file=sys.stderr)
        sys.exit(1)
    o = Out(camp)
    for f in (protocol, e1, e2, e3, e4, e5, e6):
        f(o)
    if o.problems:
        for p in o.problems:
            print("ERROR: " + p, file=sys.stderr)
        sys.exit(1)
    with open(out_path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump({"values": o.values, "sources": o.sources, "files": o.files}, fh, indent=2, sort_keys=True)
    print(f"export_campaign_numbers: {len(o.values)} values from {len(o.files)} files -> {out_path}")


if __name__ == "__main__":
    main()
