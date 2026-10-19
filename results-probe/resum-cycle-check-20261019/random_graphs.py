#!/usr/bin/env python3
"""ReSuM's ALL score (released code) against the ALL set computed from its definition, on seeded random graphs.

Usage (from the repository root):  python results-probe/resum-cycle-check-20261019/random_graphs.py <workDir> <graphs>

Graph i uses random.Random(i): 6 to 12 vertices with labels in {0, 1, 2}, n to 2n undirected edges with integer
weights 1 to 10. Each graph is run at minSup 1 and 2 with tau_w = 5. Writes <workDir>/summary.csv: graph, minSup,
whether the comparison ran, the two result counts, the patterns found only by ReSuM and only by the definition,
and how many of the ReSuM-only patterns contain a cycle (more edges than vertices minus one).
"""
import csv
import os
import random
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def codes(path):
    with open(path, encoding="utf-8", newline="") as fh:
        return {r["code"]: (int(r["vertices"]), int(r["edges"])) for r in csv.DictReader(fh, delimiter="\t")}


def main():
    out, n_graphs = sys.argv[1], int(sys.argv[2])
    os.makedirs(out, exist_ok=True)
    rows = []
    for gi in range(n_graphs):
        rnd = random.Random(gi)
        nv = rnd.randint(6, 12)
        labels = [rnd.randint(0, 2) for _ in range(nv)]
        edges = {}
        for _ in range(rnd.randint(nv, 2 * nv)):
            u, v = rnd.sample(range(nv), 2)
            edges[(min(u, v), max(u, v))] = rnd.randint(1, 10)
        name = f"rg{gi}"
        d = os.path.join(out, name)
        os.makedirs(d, exist_ok=True)
        lg = os.path.join(d, name + ".lg")
        with open(lg, "w", encoding="utf-8", newline="\n") as fh:
            fh.write("t # 1\n" + "".join(f"v {i} {l}\n" for i, l in enumerate(labels))
                     + "".join(f"e {u} {v} {w}.0\n" for (u, v), w in sorted(edges.items())))
        for s in (1, 2):
            pdir = os.path.join(d, f"patterns_{s}")
            shutil.rmtree(pdir, ignore_errors=True)
            csvf = os.path.join(d, f"resum_{s}.csv")
            if os.path.exists(csvf):
                os.remove(csvf)
            r = subprocess.run([sys.executable, os.path.join(ROOT, "tools", "run_resum.py"), csvf, lg, str(s), "5",
                                "60000", "1", "1", pdir], cwd=ROOT, capture_output=True, text=True)
            fa = os.path.join(pdir, f"{name}_ReSuM-ALL_{s}_5.0.tsv")
            if r.returncode != 0 or not os.path.exists(fa):
                rows.append([name, s, 0, "", "", "", "", ""])
                continue
            subprocess.run(["java", "-cp", os.path.join(ROOT, "target", "classes"), "welt.runner.ResumMain", "definition",
                            lg, str(s), "5", pdir], cwd=ROOT, capture_output=True, text=True, check=True)
            a, b = codes(fa), codes(os.path.join(pdir, f"{name}_ALL-definition_{s}_5.0.tsv"))
            only_a = a.keys() - b.keys()
            cyclic = sum(1 for c in only_a if a[c][1] > a[c][0] - 1)
            rows.append([name, s, 1, len(a), len(b), len(only_a), len(b.keys() - a.keys()), cyclic])
            print(name, s, rows[-1][3:], flush=True)
    with open(os.path.join(out, "summary.csv"), "w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(["graph", "minSup", "compared", "R_resum", "R_definition", "onlyResum", "onlyDefinition", "onlyResumCyclic"])
        w.writerows(rows)
    comp = [r for r in rows if r[2] == 1]
    print(f"compared {len(comp)} of {len(rows)} cells; non-empty definition {sum(1 for r in comp if r[4] > 0)}; "
          f"ReSuM-only patterns in {sum(1 for r in comp if r[5] > 0)} cells ({sum(r[5] for r in comp)} patterns, "
          f"{sum(r[7] for r in comp)} cyclic); definition-only patterns in {sum(1 for r in comp if r[6] > 0)} cells")


if __name__ == "__main__":
    main()
