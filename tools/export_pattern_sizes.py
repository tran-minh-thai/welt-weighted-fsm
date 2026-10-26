#!/usr/bin/env python3
"""Sizes of the mined patterns in the efficiency experiment, from the pattern dumps of a campaign.

    python3 tools/export_pattern_sizes.py <campaign dir> <out dir> [--self-test]

For every cell of B1_efficiency.csv and B1_low.csv where WeLT completes, reads the WeLT dump
(patterns/<dataset>_WeLT_<minSup>_<minWeight>.tsv: one result pattern per line with its vertex and edge
counts) and, where GraMi completes, the GraMi dump (the frequent patterns). The number of lines of a
dump must equal the result count of its CSV row (R), or the tool stops. Writes <out dir>/numbers.json
(a flat object, standard library only):

  e1.cell.<ds>.<s>.WeLT.R.{edges_mean,edges_max,vertices_mean}   per cell where WeLT completes
  e1.cell.<ds>.<s>.GraMi.Q.{edges_mean,edges_max,vertices_mean}  per cell where GraMi completes
  e1.size.R.edges_mean.{min,max}, e1.size.R.edges_max, e1.size.R.edges_max_cell   over the WeLT cells
  e1.size.Q.edges_mean.{min,max}, e1.size.Q.edges_max, e1.size.Q.edges_max_cell   over the GraMi cells
  e1.size.R.cells, e1.size.Q.cells
  e1.size.R_where_Q.{edges_mean.min,edges_mean.max,edges_max,cells}   R over the cells where GraMi completes,
                                            so that R and Q are compared on the same cells

A cell with an empty result set gets no mean (no key) and is not counted in the summaries.
--self-test checks that a dump with one line removed, and a dump whose edge column is changed, are
caught or change the summary.
"""
import csv
import json
import shutil
import statistics
import sys
import tempfile
from pathlib import Path


def ds(name):
    return name[:-3] if name.endswith(".lg") else name


def dump(cdir, d, algo, s, w):
    f = Path(cdir) / "patterns" / f"{d}_{algo}_{s}_{float(w)}.tsv"
    if not f.exists():
        return None
    rows = list(csv.DictReader(open(f, encoding="utf-8"), delimiter="\t"))
    return [(int(r["vertices"]), int(r["edges"])) for r in rows]


def sizes(cdir):
    cells = {}
    for name in ("B1_efficiency.csv", "B1_low.csv"):
        for r in csv.DictReader(open(Path(cdir) / name, encoding="utf-8")):
            if r["algorithm"] in ("WeLT", "GraMi") and r["status"] == "OK":
                cells[(ds(r["dataset"]), int(r["minSup"]), r["algorithm"])] = (r["minWeight"], int(r["R"]))
    keys, per = {}, {"R": [], "Q": []}
    for (d, s, algo), (w, n) in sorted(cells.items()):
        p = dump(cdir, d, algo, s, w)
        if p is None:
            sys.exit(f"export_pattern_sizes: no dump for {d} {algo} minSup={s} minWeight={w}")
        if len(p) != n:
            sys.exit(f"export_pattern_sizes: {d} {algo} minSup={s}: dump has {len(p)} patterns, the CSV row {n}")
        tag = "R" if algo == "WeLT" else "Q"
        if not p:
            continue
        b = f"e1.cell.{d}.{s}.{algo}.{tag}"
        em, ex, vm = statistics.mean(e for _v, e in p), max(e for _v, e in p), statistics.mean(v for v, _e in p)
        keys[f"{b}.edges_mean"], keys[f"{b}.edges_max"], keys[f"{b}.vertices_mean"] = em, ex, vm
        per[tag].append((em, ex, f"{d} {s}"))
    qcells = {c for _m, _x, c in per["Q"]}
    per["R_where_Q"] = [r for r in per["R"] if r[2] in qcells]
    for tag, rows in per.items():
        if not rows:
            continue
        keys[f"e1.size.{tag}.cells"] = len(rows)
        keys[f"e1.size.{tag}.edges_mean.min"] = min(r[0] for r in rows)
        keys[f"e1.size.{tag}.edges_mean.max"] = max(r[0] for r in rows)
        top = max(rows, key=lambda r: r[1])
        keys[f"e1.size.{tag}.edges_max"] = top[1]
        keys[f"e1.size.{tag}.edges_max_cell"] = "; ".join(r[2] for r in rows if r[1] == top[1])
    return keys


def main():
    cdir, out = sys.argv[1], Path(sys.argv[2])
    keys = sizes(cdir)
    out.mkdir(parents=True, exist_ok=True)
    (out / "numbers.json").write_text(json.dumps(keys, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    for k in sorted(keys):
        if k.startswith("e1.size."):
            print(k, keys[k])
    print(f"export_pattern_sizes: {sum(1 for k in keys if k.endswith('.edges_mean') and k.startswith('e1.cell.'))} cells")
    if "--self-test" in sys.argv:
        caught = 0
        with tempfile.TemporaryDirectory() as t:
            for case in ("drop a line", "change an edge count"):
                tc = Path(t) / case.replace(" ", "_")
                shutil.copytree(cdir, tc, ignore=shutil.ignore_patterns("*.log"))
                f = tc / "patterns" / "email_eu_WeLT_3_336.0.tsv"
                lines = f.read_text(encoding="utf-8").splitlines(keepends=True)
                if case == "drop a line":
                    f.write_text("".join(lines[:-1]), encoding="utf-8")
                else:
                    head, *body = lines
                    longest = max(range(len(body)), key=lambda i: int(body[i].split("\t")[2]))
                    cols = body[longest].split("\t")
                    cols[2] = str(int(cols[2]) + 100)
                    body[longest] = "\t".join(cols)
                    f.write_text(head + "".join(body), encoding="utf-8")
                try:
                    k2 = sizes(tc)
                    hit = k2 != keys
                except SystemExit:
                    hit = True
                caught += hit
                print(f"  {'caught' if hit else 'MISSED'}: {case}")
        print(f"self-test: {caught}/2 faults caught")
        sys.exit(0 if caught == 2 else 1)


if __name__ == "__main__":
    main()
