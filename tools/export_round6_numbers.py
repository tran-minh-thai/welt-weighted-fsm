#!/usr/bin/env python3
"""Export the numbers of the round-6 campaign (revision.sh r6abl, r6etb) as one JSON data file.

    python tools/export_round6_numbers.py <round-6 dir> <r2 dir> <round-5 dir> <out.json>
    python tools/export_round6_numbers.py --self-test <round-6 dir> <r2 dir> <round-5 dir>

Writes every value the paper may cite, without formatting or labels, each with its source file (path in
its campaign branch, and SHA-256). Sections:
  r6.protocol   JDK lock equal to the r2 lock, run records, source tree;
  e2.k1         ablation at email_eu (R6_ablation_wext.csv): per support and variant (full, noWextP2,
                noWextP1P2) the status, and for a completed run N, M, time and spread; for a variant its
                time ratio to full and the ablation verdict (the win rule of section 5.2 with the floor
                1.10: tie rule, a search counter in the same direction, ratio outside 1/1.10..1.10);
  noTableBuild  WeLT-noTableBuild (R6_edge_type_bound.csv) at the efficiency configurations
                (e1.cell.<ds>.<tau_s>.noTableBuild.*) and the round-5 scalability cells
                (r5s.<ds>.<tau_s>.<tau_w>.noTableBuild.*): status, R, N, M, time, spread; against WeLT of
                the r2 campaign (B1_efficiency.csv, B1_low.csv) or of round 5 (R5_scal_high_tau_w.csv), the
                verdict of the win rule without the floor from WeLT's side (welt_vs_ntb: faster means WeLT
                is faster) with the condition that decided it, and the time ratio ntb_over_welt; counts and
                ratio ranges per group (e1.count.welt_vs_ntb.*, e1.ntb_over_welt.ratio.*, r5s.* likewise).
                The two times come from different sessions on the same machine and JDK.
Exits 1, naming each failed condition, when the numbers may not be cited: a JDK lock other than the r2
lock, a run record on a modified tree, a row whose counters differ across its runs, an ablation variant
whose result set differs from full, a WeLT-noTableBuild result set (pattern dump) that differs from WeLT's,
or a missing file. --self-test injects one fault per condition into copies and checks the rule on
synthetic rows.
"""
import csv
import math
import os
import shutil
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import export_round5_numbers as r5  # noqa: E402

sc = r5.sc
MIN_EFFECT = 1.10
FILES = ("environment.lock", "src.lock", "R6_ablation_wext.csv", "R6_edge_type_bound.csv")
TAU_W = {"citeseer": 96, "email_eu": 336, "lastfm": 40, "mico": 100, "github": 30, "string_ecoli": 750}


class Out(r5.Out):
    """Sources may also lie in the r2 and round-5 campaign directories, recorded under their branch."""

    def __init__(self, camp, others):
        super().__init__(camp)
        self.others = others   # {directory: branch label}

    def src(self, name):
        if os.path.isabs(name):
            for d, label in self.others.items():
                if os.path.abspath(name).startswith(os.path.abspath(d) + os.sep):
                    key = f"{label}:{os.path.relpath(name, d).replace(os.sep, '/')}"
                    self.files[key] = r5.sha256(name)
                    return key
            raise ValueError(f"source outside the campaign directories: {name}")
        return super().src(name)


def searches(r):
    return int(r["mniIsoCallsMine"]) + int(r["weightIsoCalls"])


def reason(a, b, floor):
    """(verdict, deciding condition) for a against b: sigma (tie rule), direction (no search counter in the
    direction of the time), floor (ratio within 1/1.10..1.10), or marked."""
    t = sc.verdict(a, b)
    if t == "tie":
        return "tie", "sigma"
    sign = -1 if t == "faster" else 1
    counters = [int(a["M"]) - int(b["M"]), searches(a) - searches(b), int(a["backtrack"]) - int(b["backtrack"])]
    if not any(c * sign > 0 for c in counters):
        return "tie", "direction"
    x = float(a["totalMs"]) / float(b["totalMs"])
    if floor and 1 / MIN_EFFECT < x < MIN_EFFECT:
        return "tie", "floor"
    return t, "marked"


def codes(path):
    with open(path, encoding="utf-8") as fh:
        next(fh)
        return {line.split("\t")[0] for line in fh if line.strip()}


def k1(o):
    rows = o.rows("R6_ablation_wext.csv")
    by = {}
    for r in rows:
        o.need((int(r["minSup"]), r["variant"]) not in by, f"R6_ablation_wext.csv: {r['minSup']} {r['variant']} twice")
        by[(int(r["minSup"]), r["variant"])] = r
    for (s, v), r in sorted(by.items()):
        base = f"e2.k1.{v}.email_eu.{s}"
        o.put(base + ".status", r["status"], "R6_ablation_wext.csv")
        if r["status"] != "OK":
            continue
        for k in ("N", "M"):
            o.put(f"{base}.{k}", int(r[k]), "R6_ablation_wext.csv")
        o.put(base + ".ms", float(r["totalMs"]), "R6_ablation_wext.csv")
        o.put(base + ".sd_ms", float(r["totalSdMs"]), "R6_ablation_wext.csv")
        if v == "full":
            continue
        o.need(r["invariant"] == "OK", f"R6_ablation_wext.csv: {s} {v}: invariant {r['invariant']}")
        f = by.get((s, "full"))
        o.need(f is not None and f["status"] == "OK", f"R6_ablation_wext.csv: {s}: no completed full row")
        if f is None or f["status"] != "OK":
            continue
        o.put(base + ".ratio", float(r["totalMs"]) / float(f["totalMs"]), "R6_ablation_wext.csv")
        t, why = reason(r, f, floor=True)
        o.put(base + ".verdict", t, "R6_ablation_wext.csv")
        o.put(base + ".verdict_reason", why, "R6_ablation_wext.csv")
    o.put("e2.k1.rows", len(rows), "R6_ablation_wext.csv")


def ntb(o, r2, rd5):
    welt = {}
    for d, names in ((r2, ("B1_efficiency.csv", "B1_low.csv")), (rd5, ("R5_scal_high_tau_w.csv",))):
        for n in names:
            p = os.path.join(d, n)
            o.need(os.path.isfile(p), f"{p}: missing")
            if not os.path.isfile(p):
                continue
            with open(p, newline="", encoding="utf-8") as fh:
                for r in csv.DictReader(fh):
                    if r["algorithm"] == "WeLT":
                        welt[(r5.ds(r), int(r["minSup"]), round(float(r["minWeight"]), 3))] = (r, p, d)
    rows = o.rows("R6_edge_type_bound.csv")
    r5.deterministic(o, "R6_edge_type_bound.csv", rows)
    groups = {"e1": [], "r5s": []}
    compared = 0
    for r in rows:
        d, s, tw = r5.ds(r), int(r["minSup"]), float(r["minWeight"])
        main_tw = abs(tw - TAU_W[d]) < 1e-9
        g = "e1" if main_tw else "r5s"
        base = f"e1.cell.{d}.{s}.noTableBuild" if main_tw else f"r5s.{d}.{s}.{r5.tau_key(tw)}.noTableBuild"
        o.put(base + ".status", r["status"], "R6_edge_type_bound.csv")
        if r["status"] != "OK":
            continue
        for k in ("R", "N", "M"):
            o.put(f"{base}.{k}", int(r[k]), "R6_edge_type_bound.csv")
        o.put(base + ".ms", float(r["totalMs"]), "R6_edge_type_bound.csv")
        o.put(base + ".sd_ms", float(r["totalSdMs"]), "R6_edge_type_bound.csv")
        w = welt.get((d, s, round(tw, 3)))
        o.need(w is not None, f"{d} {s} {tw}: no WeLT row")
        if w is None:
            continue
        wr, wpath, wdir = w
        if wr["status"] != "OK":
            continue
        o.need(wr["R"] == r["R"], f"{d} {s} {tw}: |R| {r['R']} differs from WeLT's {wr['R']}")
        # result sets: the pattern dump of each run (the file name carries the exact tau_w)
        mine = [f for f in os.listdir(os.path.join(o.camp, "patterns"))
                if f.startswith(f"{d}_WeLT-noTableBuild_{s}_") and abs(float(f[:-4].rsplit("_", 1)[1]) - tw) < 1e-3]
        theirs = [f for f in os.listdir(os.path.join(wdir, "patterns"))
                  if f.startswith(f"{d}_WeLT_{s}_") and abs(float(f[:-4].rsplit("_", 1)[1]) - tw) < 1e-3]
        o.need(len(mine) == 1 and len(theirs) == 1, f"{d} {s} {tw}: {len(mine)} and {len(theirs)} pattern dumps (one each expected)")
        if len(mine) == 1 and len(theirs) == 1:
            same = codes(os.path.join(o.camp, "patterns", mine[0])) == codes(os.path.join(wdir, "patterns", theirs[0]))
            o.need(same, f"{d} {s} {tw}: result set differs from WeLT's")
            compared += 1
        t, why = reason(wr, r, floor=False)
        x = float(r["totalMs"]) / float(wr["totalMs"])
        o.put(base + ".welt_vs_ntb", t, wpath)
        o.put(base + ".welt_vs_ntb_reason", why, wpath)
        o.put(base + ".ntb_over_welt", x, wpath)
        groups[g].append((t, x))
    for g, vals in groups.items():
        o.put(f"{g}.ntb.cells", len(vals), "R6_edge_type_bound.csv")
        for t in ("faster", "slower", "tie"):
            o.put(f"{g}.count.welt_vs_ntb.{t}", sum(1 for v in vals if v[0] == t), "R6_edge_type_bound.csv")
        if vals:
            o.put(f"{g}.ntb_over_welt.ratio.min", min(v[1] for v in vals), "R6_edge_type_bound.csv")
            o.put(f"{g}.ntb_over_welt.ratio.max", max(v[1] for v in vals), "R6_edge_type_bound.csv")
    o.put("e1.ntb.result_sets_compared", compared, "R6_edge_type_bound.csv")


def export(camp, r2, rd5, out_path):
    missing = [f for f in FILES if not os.path.isfile(os.path.join(camp, f))]
    if missing:
        return [f"{f}: missing" for f in missing], None
    o = Out(camp, {r2: "campaign-revision-r2", rd5: "campaign-round5"})
    r5.protocol(o, prefix="r6")
    k1(o)
    ntb(o, r2, rd5)
    if o.problems:
        return o.problems, None
    if out_path:
        import json
        with open(out_path, "w", encoding="utf-8", newline="\n") as fh:
            json.dump({"values": o.values, "sources": o.sources, "files": o.files}, fh, indent=2, sort_keys=True)
    return [], o


def self_test(camp, r2, rd5):
    """The rule on synthetic rows, then one fault per refusal in a fresh copy of the round-6 directory."""
    ok = total = 0

    def check(name, got, want):
        nonlocal ok, total
        total += 1
        ok += got == want
        print(f"{'ok   ' if got == want else 'FAIL '} {name}: {got}")

    row = lambda ms, sd, m, s, bt: {"totalMs": ms, "totalSdMs": sd, "M": m, "mniIsoCallsMine": s, "weightIsoCalls": 0, "backtrack": bt}
    check("faster beyond the tie rule with fewer counters", reason(row(10, 1, 5, 5, 5), row(20, 1, 9, 9, 9), False), ("faster", "marked"))
    check("within three spreads is a tie", reason(row(10, 3, 5, 5, 5), row(20, 3, 9, 9, 9), False), ("tie", "sigma"))
    check("no counter in the direction of the time", reason(row(10, 1, 9, 9, 9), row(20, 1, 9, 9, 9), False), ("tie", "direction"))
    check("the floor applies only to the ablation", reason(row(100, 0.1, 5, 5, 5), row(105, 0.1, 9, 9, 9), True), ("tie", "floor"))
    check("without the floor the same pair is marked", reason(row(100, 0.1, 5, 5, 5), row(105, 0.1, 9, 9, 9), False), ("faster", "marked"))

    def edit(path, old, new):
        s = open(path, encoding="utf-8").read()
        assert old in s, f"self-test: {old!r} not in {path}"
        open(path, "w", encoding="utf-8").write(s.replace(old, new, 1))

    def set_field(path, field, value, pick=lambda r: True):
        with open(path, newline="", encoding="utf-8") as fh:
            rows = list(csv.DictReader(fh))
        r = next(r for r in rows if pick(r))
        r[field] = value
        with open(path, "w", newline="", encoding="utf-8") as fh:
            w = csv.DictWriter(fh, fieldnames=list(rows[0]))
            w.writeheader()
            w.writerows(rows)

    def drop_pattern(d):
        p = os.path.join(d, "patterns")
        f = sorted(x for x in os.listdir(p) if "_WeLT-noTableBuild_" in x and os.path.getsize(os.path.join(p, x)) > 60)[0]
        lines = open(os.path.join(p, f), encoding="utf-8").read().splitlines(True)
        open(os.path.join(p, f), "w", encoding="utf-8").write("".join(lines[:-1]))

    meta = sorted(f for f in os.listdir(camp) if f.startswith("meta_") and "r6etb" in f)[0]
    faults = [
        ("the unchanged copy is exported", None),
        ("a JDK lock other than r2", lambda d: edit(os.path.join(d, "environment.lock"), "Microsoft", "Oracle")),
        ("a run record on a modified tree", lambda d: edit(os.path.join(d, meta), '"clean"', '"MODIFIED (1 files)"')),
        ("counters differ across runs", lambda d: set_field(os.path.join(d, "R6_edge_type_bound.csv"), "deterministic", "0")),
        ("an ablation variant with another result set", lambda d: set_field(os.path.join(d, "R6_ablation_wext.csv"), "invariant", "FAIL",
                                                                              lambda r: r["variant"] != "full" and r["status"] == "OK")),
        ("a result-set size other than WeLT's", lambda d: set_field(os.path.join(d, "R6_edge_type_bound.csv"), "R", "999")),
        ("a pattern missing from a result set", drop_pattern),
        ("a missing file", lambda d: os.remove(os.path.join(d, "R6_edge_type_bound.csv"))),
    ]
    for name, fault in faults:
        with tempfile.TemporaryDirectory() as t:
            d = os.path.join(t, "camp")
            shutil.copytree(camp, d)
            if fault:
                fault(d)
            problems, _o = export(d, r2, rd5, None)
        caught = (not problems) if fault is None else bool(problems)
        total += 1
        ok += caught
        print(f"{'ok   ' if caught else 'FAIL '} {name}: {'; '.join(problems)[:110] if problems else 'exported'}")
    print(f"export_round6_numbers self-test: {ok}/{total}")
    return ok == total


def main():
    if len(sys.argv) == 5 and sys.argv[1] == "--self-test":
        sys.exit(0 if self_test(*sys.argv[2:]) else 1)
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    problems, o = export(*sys.argv[1:])
    if problems:
        for p in problems:
            print("ERROR: " + p, file=sys.stderr)
        sys.exit(1)
    print(f"export_round6_numbers: {len(o.values)} values from {len(o.files)} files -> {sys.argv[4]}")


if __name__ == "__main__":
    main()
