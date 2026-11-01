#!/usr/bin/env python3
"""Numbers of the case study, read from the patterns.csv written by case_study.py.

Three patterns are selected by their structure, never by row position:

  largest  the pattern with the most vertices; refused when two patterns share that size
  path     the pattern that is a path whose vertex labels, read along the path, are the labels
           given by --path (in either direction); a path with the same labels in another order
           is a different pattern and is not selected
  edge     the one-edge pattern whose two vertex labels are those given by --edge

Keys (prefix case.<dataset>.):
  largest.{vertices,mni,occurrences,all,edge_w_min,edge_w_max,images_heavy_single,images_heavy_max}
      edge_w_*: weights of the pattern edges in the heaviest occurrence (the one whose lightest
      edge equals W); images_heavy_*: images per pattern vertex in G restricted to edges of weight
      >= tau_w (how many vertices have one image, and the largest count)
  path.{mni,all,images_g_min,images_heavy_max}
  edge.{mni,all}

Usage:
  export_case_study_numbers.py <patterns.csv> <dataset> --path 4,4,14 --edge 4,4 --out <numbers.json>
  export_case_study_numbers.py --self-test
"""
import argparse
import csv
import json
import sys


def ints(s):
    return [int(x) for x in s.split()]


def pattern_edges(row):
    return [tuple(int(v) for v in e.split("-")) for e in row["pattern_edges"].split()]


def path_labels(row):
    """Vertex labels read along the pattern if it is a path, else None."""
    n, edges, lab = int(row["vertices"]), pattern_edges(row), ints(row["vertex_labels"])
    if len(edges) != n - 1:
        return None
    adj = {v: [] for v in range(n)}
    for a, b in edges:
        adj[a].append(b)
        adj[b].append(a)
    if any(len(x) > 2 for x in adj.values()):
        return None
    ends = [v for v in range(n) if len(adj[v]) <= 1]
    order, prev, cur = [], None, ends[0]
    while cur is not None:
        order.append(cur)
        nxt = [w for w in adj[cur] if w != prev]
        prev, cur = cur, (nxt[0] if nxt else None)
    return [lab[v] for v in order] if len(order) == n else None


def one(rows, what):
    if len(rows) != 1:
        sys.exit(f"{what}: {len(rows)} patterns match, expected exactly 1")
    return rows[0]


def select(rows, path, edge):
    top = max(int(r["vertices"]) for r in rows)
    largest = one([r for r in rows if int(r["vertices"]) == top], f"largest ({top} vertices)")
    p = one([r for r in rows if path_labels(r) in (path, path[::-1])], f"path {path}")
    e = one([r for r in rows if int(r["vertices"]) == 2 and sorted(ints(r["vertex_labels"])) == sorted(edge)],
            f"edge {edge}")
    return largest, p, e


def numbers(rows, dataset, path, edge):
    largest, p, e = select(rows, path, edge)
    k = f"case.{dataset}."
    w = [float(x) for x in largest["heaviest_edge_weights"].split()]
    heavy = ints(largest["images_ge"])
    v = {
        k + "largest.vertices": int(largest["vertices"]),
        k + "largest.mni": int(largest["mni_G"]),
        k + "largest.occurrences": int(largest["occurrences"]),
        k + "largest.all": int(largest["all_score"]),
        k + "largest.edge_w_min": min(w),
        k + "largest.edge_w_max": max(w),
        k + "largest.images_heavy_single": sum(1 for x in heavy if x == 1),
        k + "largest.images_heavy_max": max(heavy),
        k + "path.mni": int(p["mni_G"]),
        k + "path.all": int(p["all_score"]),
        k + "path.images_g_min": min(ints(p["images_G"])),
        k + "path.images_heavy_max": max(ints(p["images_ge"])),
        k + "edge.mni": int(e["mni_G"]),
        k + "edge.all": int(e["all_score"]),
    }
    if v[k + "largest.mni"] != min(ints(largest["images_G"])) or v[k + "largest.all"] != min(heavy):
        sys.exit("patterns.csv: mni_G or all_score disagrees with the images columns")
    return v


def self_test():
    """Rows in the format of patterns.csv; the decoy path 4-14-4 has the same labels as 4-4-14."""
    head = ["vertices", "vertex_labels", "pattern_edges", "mni_G", "all_score", "occurrences",
            "images_G", "images_ge", "heaviest_edge_weights"]
    data = [
        ["2", "4 4", "0-1", "35", "5", "34", "35 35", "5 5", "482"],
        ["2", "4 14", "0-1", "30", "3", "64", "30 36", "3 3", "2409"],
        ["3", "4 4 14", "0-1 1-2", "19", "2", "138", "25 19 25", "3 2 3", "482 2409"],
        ["3", "4 14 4", "0-1 1-2", "16", "1", "46", "24 16 24", "2 1 2", "351 361"],
        ["4", "4 4 4 14", "0-1 0-2 0-3", "13", "1", "195", "21 13 21 21", "2 1 2 2", "366 482 2409"],  # a star
        ["5", "4 4 4 21 14", "0-1 1-2 2-3 1-4", "10", "1", "300", "18 12 10 10 20", "1 1 1 1 2", "482 366 488 2409"],
    ]
    rows = [dict(zip(head, r)) for r in data]
    fails = 0

    def check(name, got, want):
        nonlocal fails
        print(("ok   " if got == want else "FAIL ") + f"{name}: got {got}, want {want}")
        fails += got != want

    v = numbers(rows, "t", [4, 4, 14], [4, 4])
    check("largest: mni, occurrences, all", (v["case.t.largest.mni"], v["case.t.largest.occurrences"],
                                             v["case.t.largest.all"]), (10, 300, 1))
    check("largest: edge weights of the heaviest occurrence", (v["case.t.largest.edge_w_min"],
                                                                v["case.t.largest.edge_w_max"]), (366.0, 2409.0))
    check("largest: vertices with one image, largest count", (v["case.t.largest.images_heavy_single"],
                                                               v["case.t.largest.images_heavy_max"]), (4, 2))
    check("path 4-4-14, not 4-14-4", (v["case.t.path.images_g_min"], v["case.t.path.images_heavy_max"]), (19, 3))
    check("path 14-4-4 given reversed", numbers(rows, "t", [14, 4, 4], [4, 4])["case.t.path.mni"], 19)
    check("path 4-14-4 is its own pattern", numbers(rows, "t", [4, 14, 4], [4, 4])["case.t.path.mni"], 16)
    check("a star is not a path", path_labels(rows[4]), None)
    check("edge 4-4, not 4-14", (v["case.t.edge.mni"], v["case.t.edge.all"]), (35, 5))
    print(f"export_case_study_numbers self-test: {'OK' if fails == 0 else 'FAILED'} ({8 - fails}/8)")
    return 1 if fails else 0


def main():
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    ap = argparse.ArgumentParser()
    ap.add_argument("patterns")
    ap.add_argument("dataset")
    ap.add_argument("--path", required=True, help="labels along the path, comma separated")
    ap.add_argument("--edge", required=True, help="the two labels of the one-edge pattern, comma separated")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    with open(a.patterns, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    v = numbers(rows, a.dataset, [int(x) for x in a.path.split(",")], [int(x) for x in a.edge.split(",")])
    with open(a.out, "w", encoding="utf-8", newline="\n") as f:
        json.dump({"values": v}, f, indent=1, sort_keys=True)
        f.write("\n")
    for key in sorted(v):
        print(f"{key}\t{v[key]:g}")
    print(f"patterns: {len(rows)}; keys written: {len(v)}")


if __name__ == "__main__":
    main()
