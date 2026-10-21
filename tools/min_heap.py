#!/usr/bin/env python3
"""Smallest Java heap at which one cell completes (standard library only).

Usage:
  python tools/min_heap.py <out.csv> <log_dir> <dataset.lg> <minSup> <minWeight> <algorithm>
                           [--max-mb N] [--min-mb 4] [--ratio 1.05] [--first-limit-ms F]
                           [--slowdown 2] [--min-limit-ms 1000] [--limit-ms L]

Each probe is one JVM running the cell once (BenchmarkMain, no warm-up, one measured run) with
-Xmx set to the probed size; the other options of JAVA_OPTS are kept, -Xmx and -Xms are replaced.
A probe passes when its status is OK. The first probe runs at --max-mb (default: the -Xmx of
JAVA_OPTS), with the time limit --first-limit-ms (default 3600000, the campaign limit); a cell that
does not complete there gets a row with its status and no heap. The time
limit of the other probes is --limit-ms, by default --slowdown times the run time at --max-mb and at
least --min-limit-ms: the size found is the smallest heap at which the cell runs at most that much
slower than with the full heap, so a heap that completes only by collecting garbage most of the
time counts as too small. Times are the in-process run times of BenchmarkMain (JVM start excluded).
The search is a bisection on a geometric scale between a failing and a passing size, until their
ratio is at most --ratio; the passing size found is then run once more (confirmed=1 when it passes
again). Row: heapOkMB (smallest passing size), heapFailMB (largest failing size), the probes, their
failing statuses and times. Every probe is appended to <log_dir>/<dataset>_<algorithm>_<minSup>.log.
A cell that already has a row in out.csv is skipped.
"""
import argparse
import csv
import math
import os
import re
import shlex
import subprocess
import sys
import time

FIELDS = ["dataset", "algorithm", "minSup", "minWeight", "statusAtMax", "heapMaxMB", "timeAtMaxMs", "slowdown", "limitMs",
          "heapOkMB", "heapFailMB", "ratio", "probes", "failStatuses", "timeAtOkMs", "confirmed"]


def search(run_at, max_mb, min_mb, ratio):
    """Bisection for the smallest passing heap. run_at(mb) -> (status, totalMs).

    Returns a dict with the FIELDS that the search decides, and the list of probes (mb, status, ms).
    """
    probes = []

    def probe(mb):
        st, ms = run_at(mb)
        probes.append((mb, st, ms))
        return st, ms

    st, t_max = probe(max_mb)
    out = {"statusAtMax": st, "heapMaxMB": max_mb, "timeAtMaxMs": t_max}
    if st != "OK":
        out.update(heapOkMB="", heapFailMB="", timeAtOkMs="", confirmed="")
        return out, probes
    ok, ok_ms = max_mb, t_max
    st, ms = probe(min_mb)
    if st == "OK":
        out.update(heapOkMB=min_mb, heapFailMB="", timeAtOkMs=ms, confirmed=1)
        return out, probes
    fail = min_mb
    while ok / fail > ratio:
        mid = int(round(math.sqrt(ok * fail)))
        if mid <= fail or mid >= ok:
            break
        st, ms = probe(mid)
        if st == "OK":
            ok, ok_ms = mid, ms
        else:
            fail = mid
    st, ms = probe(ok)
    out.update(heapOkMB=ok, heapFailMB=fail, timeAtOkMs=ok_ms, confirmed=1 if st == "OK" else 0)
    return out, probes


def heap_mb_of(opts):
    m = re.search(r"-Xmx(\d+)([kKmMgG]?)", opts)
    if not m:
        return None
    n, u = int(m.group(1)), m.group(2).lower()
    return n // 1024 if u == "k" else n if u == "m" else n * 1024 if u == "g" else n // (1024 * 1024)


def done(path, key):
    if not os.path.exists(path) or os.path.getsize(path) == 0:
        return False
    with open(path, newline="", encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            if r["dataset"] == key[0] and r["algorithm"] == key[1] and int(r["minSup"]) == int(key[2]) \
                    and float(r["minWeight"]) == float(key[3]):
                return True
    return False


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for a in ("out", "log_dir", "dataset", "minSup", "minWeight", "algorithm"):
        ap.add_argument(a)
    ap.add_argument("--max-mb", type=int)
    ap.add_argument("--min-mb", type=int, default=4)
    ap.add_argument("--ratio", type=float, default=1.05)
    ap.add_argument("--first-limit-ms", type=int, default=3600000)
    ap.add_argument("--slowdown", type=float, default=2.0)
    ap.add_argument("--min-limit-ms", type=int, default=1000)
    ap.add_argument("--limit-ms", type=int)
    a = ap.parse_args()
    opts = os.environ.get("JAVA_OPTS", "")
    max_mb = a.max_mb or heap_mb_of(opts)
    if not max_mb:
        sys.exit("min_heap: give --max-mb or set -Xmx in JAVA_OPTS")
    base = [o for o in shlex.split(opts) if not o.startswith(("-Xmx", "-Xms"))]
    ds = os.path.basename(a.dataset)
    key = (ds, a.algorithm, a.minSup, a.minWeight)
    if done(a.out, key):
        print(f"--- skip (already in {os.path.basename(a.out)}): min heap {a.algorithm} {ds} minSup={a.minSup}")
        return
    os.makedirs(a.log_dir, exist_ok=True)
    log = os.path.join(a.log_dir, f"{os.path.splitext(ds)[0]}_{a.algorithm}_{a.minSup}.log")
    limit = {"ms": a.limit_ms}
    env = dict(os.environ, BENCH_CSV="1", BENCH_ALGOS=a.algorithm)

    def run_at(mb):
        lim = limit["ms"] or a.first_limit_ms
        cmd = ["java", *base, f"-Xmx{mb}m", "-cp", "target/classes", "welt.runner.BenchmarkMain",
               a.dataset, a.minSup, a.minWeight, "0", str(lim), "0", "1"]
        t0 = time.time()
        p = subprocess.run(cmd, env=env, capture_output=True, text=True)
        rows = [l[4:] for l in p.stdout.splitlines() if l.startswith("CSV,")]
        if len(rows) >= 2:
            r = next(csv.DictReader(rows[:2]))
            st, ms = r["status"], float(r["totalMs"]) if r["totalMs"] else float("nan")
        else:
            st = "OOM" if "OutOfMemoryError" in p.stdout + p.stderr else "CRASH"
            ms = (time.time() - t0) * 1000
        with open(log, "a", encoding="utf-8") as fh:
            fh.write(f"### -Xmx{mb}m limit={lim}ms exit={p.returncode} status={st} totalMs={ms:.1f}\n")
            fh.write(" ".join(cmd) + "\n" + p.stdout + p.stderr + "\n")
        print(f"  min heap {a.algorithm} {ds} minSup={a.minSup}: -Xmx{mb}m -> {st} {ms:.0f} ms", flush=True)
        if limit["ms"] is None and mb == max_mb and st == "OK":
            limit["ms"] = max(a.min_limit_ms, int(math.ceil(a.slowdown * ms)))
        return st, ms

    out, probes = search(run_at, max_mb, a.min_mb, a.ratio)
    out.update(dataset=ds, algorithm=a.algorithm, minSup=a.minSup, minWeight=a.minWeight, ratio=a.ratio, slowdown=a.slowdown,
               limitMs=limit["ms"] or "", probes=len(probes),
               failStatuses=";".join(sorted({st for _, st, _ in probes if st != "OK"})))
    new = not os.path.exists(a.out) or os.path.getsize(a.out) == 0
    with open(a.out, "a", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=FIELDS, lineterminator="\n")
        if new:
            w.writeheader()
        w.writerow(out)
    print(f"min heap {a.algorithm} {ds} minSup={a.minSup}: ok {out['heapOkMB']} MB, fail {out['heapFailMB']} MB, "
          f"{len(probes)} probes, confirmed={out['confirmed']}")


if __name__ == "__main__":
    main()
