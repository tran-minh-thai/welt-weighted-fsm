#!/usr/bin/env python3
"""Show that tools/bruteforce_oracle.py catches faults in the miner: each fault is applied to a copy of
src/main/java, compiled, and the oracle is run against it; every fault must make the oracle exit 1.

    check_bruteforce_faults.py <work dir> [--jobs 2]

About 95 s per fault on a laptop. Prints, per fault, the counts of the oracle; exits 1 when a fault is
not caught or a fault could not be applied (its text must occur exactly once).
"""
import json
import os
import shutil
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor

CORE = "src/main/java/welt/core/"
TABLE = "src/main/java/welt/strategy/WeightedLookupTable.java"
FAULTS = {
    # an edge of weight exactly tau_w is rejected by the weight check
    "weight_ge_to_gt": (CORE + "MniSupportCounter.java", "|| e.weight < minW", "|| e.weight <= minW"),
    # an edge lighter than tau_w by up to 1 is accepted
    "weight_lenient": (CORE + "MniSupportCounter.java", "|| e.weight < minW", "|| e.weight < minW - 1"),
    # a pattern vertex with exactly tau_s images makes the pattern infrequent
    "mni_off_by_one": (CORE + "MniSupportCounter.java", "                if (valid[i].size() < minSup) {",
                       "                if (valid[i].size() <= minSup) {"),
    # a pattern vertex with tau_s - 1 images is accepted
    "mni_lenient": (CORE + "MniSupportCounter.java", "                if (valid[i].size() < minSup) {",
                    "                if (valid[i].size() < minSup - 1) {"),
    # P2 on one-edge patterns prunes an edge type whose heaviest edge equals tau_w
    "p2_single_tie": (TABLE, "if (w == null || w < minWeight) { // P2, k = 1", "if (w == null || w <= minWeight) { // P2, k = 1"),
    # the threshold table records a 2-edge path only when it embeds strictly above tau_w
    "table_strict": (TABLE, "(counter.embedsWithMinWeight(child, opt.threshold) ?",
                     "(counter.embedsWithMinWeight(child, Math.nextUp(opt.threshold)) ?"),
    # no cycle-closing edge is added, so a pattern with a cycle is never generated (edge removal lemma)
    "no_cycle_closing_edge": (CORE + "MiningEngine.java", "                if (p.hasEdge(u, w)) continue;",
                              "                if (true) continue;"),
}


def one(work, name):
    f, a, b = FAULTS[name]
    d = os.path.join(work, name)
    shutil.copytree("src", os.path.join(d, "src"), dirs_exist_ok=True)
    p = os.path.join(d, f)
    text = open(p, encoding="utf-8").read()
    if text.count(a) != 1:
        return name, None, f"text to replace occurs {text.count(a)} times in {f}"
    open(p, "w", encoding="utf-8").write(text.replace(a, b))
    srcs = [os.path.join(r, x) for r, _, fs in os.walk(os.path.join(d, "src/main/java")) for x in fs if x.endswith(".java")]
    subprocess.run(["javac", "-nowarn", "--release", "17", "-d", os.path.join(d, "classes")] + srcs, check=True, capture_output=True)
    js = os.path.join(d, "counts.json")
    r = subprocess.run([sys.executable, "tools/bruteforce_oracle.py", "run", "--classes", os.path.join(d, "classes"),
                        "--out", os.path.join(d, "run"), "--json", js], capture_output=True, text=True)
    return name, r.returncode, json.load(open(js)) if os.path.isfile(js) else r.stdout[-500:]


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    work = sys.argv[1]
    jobs = int(sys.argv[sys.argv.index("--jobs") + 1]) if "--jobs" in sys.argv else 2
    os.makedirs(work, exist_ok=True)
    with ThreadPoolExecutor(jobs) as ex:
        results = list(ex.map(lambda n: one(work, n), FAULTS))
    caught = 0
    for name, code, info in results:
        ok = code == 1
        caught += ok
        print(f"{'caught' if ok else 'MISSED'}  {name}: exit {code}, {info}")
    print(f"check_bruteforce_faults: {caught}/{len(FAULTS)} faults caught")
    sys.exit(0 if caught == len(FAULTS) else 1)


if __name__ == "__main__":
    main()
