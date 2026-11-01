#!/usr/bin/env python3
"""How many of the patterns compared by bruteforce_oracle.py carry each property the check is meant to cover.

The comparison of bruteforce_oracle.py reports no false positive, false negative or W difference, so the
patterns it compared at each (tau_s, tau_w) pair are exactly the result set of the oracle. This tool
rebuilds those result sets from the same graphs and thresholds (oracle only, no Java) and counts, over all
pairs, the patterns:

  w_equals_tau_w    whose weight W equals tau_w (kept by the >= comparison)
  repeated_labels   with two vertices of the same label
  cycles            with at least as many edges as vertices
  all_three         with the three properties at once

Usage:
  count_oracle_cases.py [--random 22] [--seed 1] [--json <file>]
  count_oracle_cases.py --self-test
"""
import argparse
import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bruteforce_oracle as bo  # noqa: E402


def properties(c, w, tw):
    vl, pe = c
    return {"w_equals_tau_w": w == tw, "repeated_labels": len(set(vl)) < len(vl), "cycles": len(pe) >= len(vl)}


def count(graphs):
    n = {"graphs": 0, "threshold_pairs": 0, "patterns": 0, "w_equals_tau_w": 0, "repeated_labels": 0,
         "cycles": 0, "all_three": 0}
    with tempfile.TemporaryDirectory() as t:
        for name, labels, edges in graphs:
            path = os.path.join(t, name + ".lg")
            bo.write_lg(path, labels, edges)        # read back like the oracle run does
            lab, weight = bo.read_lg(path)
            facts = bo.oracle(lab, weight)
            n["graphs"] += 1
            taus = sorted(set(weight.values()))
            for s in (1, 2, 3):
                for tw in taus + [max(taus) + 1]:
                    n["threshold_pairs"] += 1
                    for c, w in bo.result_set(facts, s, tw).items():
                        p = properties(c, w, tw)
                        n["patterns"] += 1
                        for k, v in p.items():
                            n[k] += v
                        n["all_three"] += all(p.values())
    return n


def self_test():
    fails = 0

    def check(name, got, want):
        nonlocal fails
        print(("ok   " if got == want else "FAIL ") + f"{name}: got {got}, want {want}")
        fails += got != want

    tri = ((1, 1, 2), ((0, 1), (0, 2), (1, 2)))
    check("triangle with labels 1 1 2 at its weight", properties(tri, 3.0, 3.0),
          {"w_equals_tau_w": True, "repeated_labels": True, "cycles": True})
    check("path 1 2 3 above the threshold", properties(((1, 2, 3), ((0, 1), (1, 2))), 5.0, 3.0),
          {"w_equals_tau_w": False, "repeated_labels": False, "cycles": False})
    # one graph: labels 1 1 2, edges 0-1 w2, 1-2 w3, 0-2 w3 (a triangle)
    n = count([("t", {0: 1, 1: 1, 2: 2}, [(0, 1, 2), (1, 2, 3), (0, 2, 3)])])
    check("pairs: tau_s 1..3 times tau_w in {2, 3, 4}", n["threshold_pairs"], 9)
    # patterns (MNI, W): 1-1 (2, 2), 1-2 (1, 3), path 1-1-2 (1, 2), path 1-2-1 (1, 3), triangle (1, 2).
    # tau_s=1: tau_w=2 keeps all five (W=2: 1-1, 1-1-2, triangle), tau_w=3 keeps 1-2 and 1-2-1 (both W=3);
    # tau_s=2: tau_w=2 keeps 1-1. Total 8; W = tau_w 3+2+1 = 6; repeated labels 4+1+1 = 6; one cycle.
    check("counts on the triangle", {k: n[k] for k in ("patterns", "w_equals_tau_w", "repeated_labels", "cycles",
                                                        "all_three")},
          {"patterns": 8, "w_equals_tau_w": 6, "repeated_labels": 6, "cycles": 1, "all_three": 1})
    print(f"count_oracle_cases self-test: {'OK' if fails == 0 else 'FAILED'} ({4 - fails}/4)")
    return 1 if fails else 0


def main():
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    ap = argparse.ArgumentParser()
    ap.add_argument("--random", type=int, default=22)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--json")
    a = ap.parse_args()
    n = count(bo.hand_graphs() + bo.random_graphs(a.random, a.seed))
    for k, v in n.items():
        print(f"{k}\t{v}")
    if a.json:
        with open(a.json, "w", encoding="utf-8", newline="\n") as f:
            json.dump(n, f, indent=1)
            f.write("\n")


if __name__ == "__main__":
    main()
