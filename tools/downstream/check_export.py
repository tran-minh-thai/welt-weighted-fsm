#!/usr/bin/env python3
"""Fault injection for export_numbers.py, on copies of real artifacts.

    python tools/downstream/check_export.py <campaign dir> <baseline-check dir> <github probe choice.json>

Copies the artifacts to a temporary directory, checks that the export of the unchanged copy
succeeds and that each injected fault makes it exit 1 with the expected message. Prints the
count of faults caught; exits 1 unless every fault is caught.
"""
import csv
import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))


def edit_json(path, f):
    with open(path, encoding="utf-8") as fh:
        d = json.load(fh)
    f(d)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(d, fh)


def replace_text(path, old, new):
    with open(path, newline="") as fh:
        text = fh.read()
    if old not in text:
        raise ValueError(f"{path}: {old!r} not found")
    with open(path, "w", newline="") as fh:
        fh.write(text.replace(old, new))


def drop_fold(path):
    with open(path, newline="") as fh:
        rows = list(csv.reader(fh))
    with open(path, "w", newline="") as fh:
        csv.writer(fh, lineterminator="\n").writerows(rows[:-1])


FAULTS = [
    ("modified tree", "lastfm/run_meta.json", lambda p: edit_json(p, lambda d: d.update(tree="MODIFIED (1 files)")), "tree"),
    ("departure from the design", "lastfm/run_meta.json",
     lambda p: edit_json(p, lambda d: d.update(departures_from_design={"folds": 3})), "departures"),
    ("raw continuous columns", "lastfm/run_meta.json",
     lambda p: edit_json(p, lambda d: d["design"].update(continuous_columns="raw")), "continuous columns"),
    ("one fold missing", "lastfm/folds.csv", drop_fold, "rows, expected"),
    ("one test missing", "lastfm/summary.json",
     lambda p: edit_json(p, lambda d: d.update(tests=[t for t in d["tests"] if not (
         t.get("other") == "B1" and t.get("metric") == "accuracy")])), "missing"),
    ("GitHub run completed", "github/run_meta.json", lambda p: edit_json(p, lambda d: d.update(outcome="completed")),
     "outcome"),
    ("baseline check differs", "checks/lastfm.json",
     lambda p: edit_json(p, lambda d: d.update(B0_macro_f1_mean=d["B0_macro_f1_mean"] + 1e-12)), "reproduce"),
    ("GitHub probe cell incomplete", "probe/downstream_choice.json",
     lambda p: edit_json(p, lambda d: [r.update(status="TO") for r in d["scanned"] if r["rho_w"] == 0.7][:1]),
     "did not complete"),
    ("seed missing from the folds", "lastfm/folds.csv",
     lambda p: replace_text(p, "\n3,", "\n4,"), "seeds"),
    ("baseline check on a modified tree", "checks/email_eu.json",
     lambda p: edit_json(p, lambda d: d["run"].update(tree="MODIFIED (2 files)")), "tree"),
]


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    camp, checks, probe = sys.argv[1:]
    caught = 0
    with tempfile.TemporaryDirectory() as tmp:
        def fresh():
            base = tempfile.mkdtemp(dir=tmp)
            os.makedirs(os.path.join(base, ".git"))   # src() resolves paths from the checkout root
            c = os.path.join(base, "camp")
            for ds in ("lastfm", "github"):
                shutil.copytree(os.path.join(camp, f"B8_downstream_{ds}"), os.path.join(c, f"B8_downstream_{ds}"),
                                ignore=shutil.ignore_patterns("selection_patterns", "image_sets", "mined", "patterns"))
            shutil.copytree(checks, os.path.join(base, "checks"))
            os.makedirs(os.path.join(base, "probe"))
            shutil.copy(probe, os.path.join(base, "probe", "downstream_choice.json"))
            return base, c

        def run(base, c):
            return subprocess.run([sys.executable, os.path.join(HERE, "export_numbers.py"), c,
                                   os.path.join(base, "checks"), os.path.join(base, "out.json"),
                                   os.path.join(base, "probe", "downstream_choice.json")],
                                  capture_output=True, text=True)
        base, c = fresh()
        r = run(base, c)
        if r.returncode != 0:
            print("FAIL  the unchanged copy is refused: " + r.stderr.strip())
            sys.exit(1)
        print("ok    the unchanged copy is exported")
        for name, rel, inject, expect in FAULTS:
            base, c = fresh()
            target = {"lastfm": os.path.join(c, "B8_downstream_lastfm"), "github": os.path.join(c, "B8_downstream_github"),
                      "checks": os.path.join(base, "checks"), "probe": os.path.join(base, "probe")}[rel.split("/")[0]]
            path = os.path.join(target, rel.split("/", 1)[1])
            before = open(path, "rb").read()
            inject(path)
            if open(path, "rb").read() == before:
                print(f"FAIL  {name}: the injection did not change {rel}")
                continue
            r = run(base, c)
            ok = r.returncode == 1 and expect in r.stderr
            caught += ok
            print(f"{'ok   ' if ok else 'FAIL '} {name}: exit {r.returncode}, {r.stderr.strip().splitlines()[0] if r.stderr.strip() else 'no message'}")
    print(f"check_export: {caught}/{len(FAULTS)} faults caught")
    sys.exit(0 if caught == len(FAULTS) else 1)


if __name__ == "__main__":
    main()
