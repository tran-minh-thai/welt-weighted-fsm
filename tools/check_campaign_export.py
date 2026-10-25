#!/usr/bin/env python3
"""Fault injection for export_campaign_numbers.py, on a copy of a real campaign directory.

    python tools/check_campaign_export.py <campaign dir>

Copies the CSV files and run records to a temporary directory, checks that the export of the
unchanged copy succeeds and gives the same values as the original, and that each injected fault
makes it exit 1 with the expected message. Prints the count of faults caught; exits 1 unless every
fault is caught. Standard library only.
"""
import csv
import glob
import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))


def edit_csv(path, pick, change):
    with open(path, newline="", encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))
        fields = list(rows[0])
    hit = [r for r in rows if pick(r)]
    if not hit:
        raise ValueError(f"{path}: no row to change")
    change(hit[0])
    with open(path, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=fields, lineterminator="\n")
        w.writeheader()
        w.writerows(rows)


def edit_meta(camp, change):
    p = sorted(glob.glob(os.path.join(camp, "meta_*.json")))[-1]
    with open(p, encoding="utf-8") as fh:
        m = json.load(fh)
    change(m)
    with open(p, "w", encoding="utf-8") as fh:
        json.dump(m, fh)


FAULTS = [
    ("run record on a modified tree", lambda c: edit_meta(c, lambda m: m.update(gitTree="MODIFIED (1 files)")), "gitTree"),
    ("run record on another JDK", lambda c: edit_meta(c, lambda m: m.update(javaRuntimeVersion="21.0.4+7")), "JDK"),
    ("counters differ across runs", lambda c: edit_csv(os.path.join(c, "B1_efficiency.csv"),
     lambda r: r["status"] == "OK" and r["algorithm"] == "WeLT", lambda r: r.update(deterministic="0")), "counters differ"),
    ("WeLT and GraMiFilter differ", lambda c: edit_csv(os.path.join(c, "B7_equivalence.csv"),
     lambda r: True, lambda r: r.update(sameSet="0")), "differ"),
    ("gating invariant broken", lambda c: edit_csv(os.path.join(c, "B1_efficiency.csv"),
     lambda r: r["status"] == "OK" and r["algorithm"] == "WeLT" and int(r["R"]) > 0,
     lambda r: r.update(extended=str(int(r["R"]) + 1))), "gating invariant"),
    ("ablation result-set violation", lambda c: edit_csv(os.path.join(c, "B4_low.csv"),
     lambda r: r["invariant"] == "OK", lambda r: r.update(invariant="MISSING")), "result-set violations"),
    ("shared table differs from WeLT", lambda c: edit_csv(os.path.join(c, "B5_shared_table.csv"),
     lambda r: r["row"] == "point", lambda r: r.update(R=str(int(r["R"]) + 1))), "differs from WeLT"),
    ("nesting violation", lambda c: edit_csv(os.path.join(c, "B7_nesting.csv"),
     lambda r: r["skeletonsNotInAll"] == "0", lambda r: r.update(skeletonsNotInAll="1")), "violation"),
    ("ALL outside WeLT", lambda c: edit_csv(os.path.join(c, "B7_all_within_welt.csv"),
     lambda r: r["onlyA"] == "0", lambda r: r.update(onlyA="1")), "outside WeLT"),
    ("default voter row missing", lambda c: edit_csv(os.path.join(c, "B2_voting_grid.csv"),
     lambda r: r["voter"].replace(";", ",") == "1,1,1", lambda r: r.update(voter="2;1;1")), "default voter"),
    ("file missing", lambda c: os.remove(os.path.join(c, "B6_wegm_range.csv")), "missing"),
]


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    camp = sys.argv[1]
    caught = 0
    with tempfile.TemporaryDirectory() as tmp:
        def fresh():
            base = tempfile.mkdtemp(dir=tmp)
            os.makedirs(os.path.join(base, ".git"))
            c = os.path.join(base, "camp")
            os.makedirs(c)
            for f in os.listdir(camp):
                p = os.path.join(camp, f)
                if os.path.isfile(p):
                    shutil.copy(p, c)
            return base, c

        def run(base, c):
            return subprocess.run([sys.executable, os.path.join(HERE, "export_campaign_numbers.py"), c,
                                   os.path.join(base, "out.json")], capture_output=True, text=True)
        base, c = fresh()
        r = run(base, c)
        if r.returncode != 0:
            print("FAIL  the unchanged copy is refused: " + r.stderr.strip())
            sys.exit(1)
        orig = os.path.join(tmp, "orig.json")
        subprocess.run([sys.executable, os.path.join(HERE, "export_campaign_numbers.py"), camp, orig],
                       capture_output=True, text=True, check=True)
        with open(orig) as a, open(os.path.join(base, "out.json")) as b:
            same = json.load(a)["values"] == json.load(b)["values"]
        print(f"{'ok   ' if same else 'FAIL '} the unchanged copy is exported with the values of the original")
        if not same:
            sys.exit(1)
        for name, inject, expect in FAULTS:
            base, c = fresh()
            before = {f: open(os.path.join(c, f), "rb").read() for f in os.listdir(c)}
            inject(c)
            after = {f: open(os.path.join(c, f), "rb").read() for f in os.listdir(c)}
            if before == after:
                print(f"FAIL  {name}: the injection changed nothing")
                continue
            r = run(base, c)
            ok = r.returncode == 1 and expect in r.stderr
            caught += ok
            first = r.stderr.strip().splitlines()[0] if r.stderr.strip() else "no message"
            print(f"{'ok   ' if ok else 'FAIL '} {name}: exit {r.returncode}, {first.replace(base, '<copy>')}")
    print(f"check_campaign_export: {caught}/{len(FAULTS)} faults caught")
    sys.exit(0 if caught == len(FAULTS) else 1)


if __name__ == "__main__":
    main()
