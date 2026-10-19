#!/usr/bin/env python3
"""Run ReSuM's ALL score on one cell and append one CSV row (standard library only).

Usage:
  python tools/run_resum.py <out.csv> <dataset.lg> <minSup> <minWeight> <limitMs> <warmup> <measured> <patternsDir>

ReSuM is built by tools/build_resum.py (third_party/resum). The dataset is exported to ReSuM's
input format (third_party/resum/data/<name>/, ExportResumMain), again whenever the SHA-256 of
the dataset differs from the one recorded with the export. The relevance threshold is the
largest double below minWeight (ExportResumMain relevance), so "every edge weighs more than the
threshold" is "every edge weighs at least minWeight".

Protocol (the one of BenchmarkMain, with one JVM per run because ReSuM is an external program):
a probe run, which is also the first warmup; a probe that does not complete within limitMs is
reported alone (status TO, or OOM / CRASH when the JVM fails); otherwise warmup - 1 further
warmups and `measured` runs. JAVA_OPTS is passed to every JVM. Time columns: `resumMs` is the
time ReSuM reports ("Found N patterns, in X ms": its stopwatch covers reading the input files,
building its index and mining), `wallMs` the elapsed time of the JVM process. Medians, minimum,
maximum and sample SD over the measured runs. The result set of the last measured run is written
to <patternsDir>/<name>_ReSuM-ALL_<minSup>_<minWeight>.tsv (ResumMain import).

Exit 0 when a row was appended, 1 on a usage or setup error (no row).
"""
import csv
import hashlib
import json
import os
import re
import shlex
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RESUM = ROOT / "third_party" / "resum"
FOUND = re.compile(r"Found (\d+) patterns, in (\d+)ms")
HEADER = ["dataset", "algorithm", "minSup", "minWeight", "relevance", "limitMs", "warmup", "runs", "status",
          "nonOkRuns", "R", "deterministic", "resumMs", "resumMinMs", "resumMaxMs", "resumSdMs",
          "wallMs", "wallMinMs", "wallMaxMs", "wallSdMs", "javaOpts", "resumCommit", "graphSha256", "weightSha256"]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def fail(msg):
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def java_cmd(*args):
    return ["java", *shlex.split(os.environ.get("JAVA_OPTS", "")), *args]


def welt_java(*args):
    r = subprocess.run(["java", "-cp", str(ROOT / "target" / "classes"), *args], capture_output=True, text=True)
    if r.returncode != 0:
        fail(f"{' '.join(args)} exited {r.returncode}:\n{r.stdout}{r.stderr}")
    return r.stdout


def export_data(dataset):
    name = dataset.name[:-3] if dataset.name.endswith(".lg") else dataset.name
    data = RESUM / "data" / name
    graph, weights, source = data / f"{name}.lg", data / f"{name}.w", data / "source.sha256"
    digest = sha256(dataset)
    current = source.exists() and source.read_text(encoding="utf-8").strip() == digest
    if not (current and graph.exists() and weights.exists()):
        data.mkdir(parents=True, exist_ok=True)
        source.unlink(missing_ok=True)
        welt_java("welt.runner.ExportResumMain", str(dataset), str(data))
        if not (graph.exists() and weights.exists()):
            fail(f"export of {dataset} wrote no files in {data}")
        source.write_text(digest + "\n", encoding="utf-8")
    return name, data, graph, weights


def one_run(name, data, min_sup, relevance, limit_ms):
    """Returns (status, patterns, resumMs, wallMs, patternFile or None, workDir)."""
    work = Path(tempfile.mkdtemp(prefix="resum_run_"))
    cmd = java_cmd("-cp", os.pathsep.join([str(RESUM / "classes"), str(RESUM / "lib" / "*")]),
                   "sa.edu.kaust.grami.und.main.Main", "caching=true", "automorphism=true",
                   f"inputFileName={name}.lg", f"weightFileName={name}.w", f"freq={min_sup}",
                   f"rel={relevance}", "score=1", "numberOfEdgeWeights=1", "actualNumOfEdgeWeights=1",
                   "maxSize=1000", "weightFileSize=1", f"datasetsFolder={data}", f"outputFolder={work}",
                   "outputFileName=statistics.tsv")
    t0 = time.perf_counter()
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=limit_ms / 1000.0)
    except subprocess.TimeoutExpired:
        return "TO", None, None, (time.perf_counter() - t0) * 1000.0, None, work
    wall = (time.perf_counter() - t0) * 1000.0
    out = r.stdout + r.stderr
    m = FOUND.search(out)
    files = list(work.glob("Patterns_*.p"))
    if r.returncode != 0 or not m or len(files) != 1 or "Exception" in out:
        status = "OOM" if "OutOfMemoryError" in out else "CRASH"
        sys.stderr.write(out[-4000:])
        return status, None, None, wall, None, work
    return "OK", int(m.group(1)), float(m.group(2)), wall, files[0], work


def stats(xs):
    if not xs:
        return ["", "", "", ""]
    sd = statistics.stdev(xs) if len(xs) > 1 else 0.0
    return [f"{statistics.median(xs):.3f}", f"{min(xs):.3f}", f"{max(xs):.3f}", f"{sd:.3f}"]


def append_row(out, row):
    exists = out.exists() and out.stat().st_size > 0
    if exists:
        with open(out, newline="", encoding="utf-8") as fh:
            head = next(csv.reader(fh), [])
        if head != HEADER:
            fail(f"header of {out} differs from the header of this tool:\n  file: {head}\n  tool: {HEADER}")
    with open(out, "a", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, lineterminator="\n")
        if not exists:
            w.writerow(HEADER)
        w.writerow(row)


def main():
    a = sys.argv[1:]
    if len(a) != 8:
        sys.exit(__doc__)
    out, dataset = Path(a[0]), Path(a[1])
    min_sup, min_weight, limit_ms = int(a[2]), float(a[3]), int(a[4])
    warmup, measured, patterns = int(a[5]), int(a[6]), Path(a[7])
    if not (RESUM / "build_info.json").exists():
        fail(f"{RESUM / 'build_info.json'} does not exist: run python tools/build_resum.py first")
    if not dataset.exists():
        fail(f"dataset {dataset} does not exist")
    commit = json.loads((RESUM / "build_info.json").read_text(encoding="utf-8"))["commit"]
    name, data, graph, weights = export_data(dataset)
    relevance = welt_java("welt.runner.ExportResumMain", "relevance", repr(min_weight)).strip()
    if not float(relevance) < min_weight:
        fail(f"relevance {relevance} is not below minWeight {min_weight}")
    print(f">>> ReSuM-ALL  {dataset.as_posix()}  minSup={min_sup}  tau_w={min_weight}  relevance={relevance}  limit={limit_ms}ms")
    plan = ["probe"] + ["warmup"] * max(0, warmup - 1) + ["measured"] * measured
    resum_ms, wall_ms, counts, status, non_ok, last_file, works = [], [], [], "OK", 0, None, []
    for i, kind in enumerate(plan):
        st, n, rms, wms, pfile, work = one_run(name, data, min_sup, relevance, limit_ms)
        works.append(work)
        print(f"    run {i + 1}/{len(plan)} ({kind}): {st}" + (f", {n} patterns, {rms:.0f} ms" if st == "OK" else ""))
        if st != "OK":
            if kind == "probe":
                status = st
                break
            non_ok += 1
            status = st
            continue
        counts.append(n)
        if kind == "measured":
            resum_ms.append(rms)
            wall_ms.append(wms)
            last_file = pfile
    det = "" if not counts else int(len(set(counts)) == 1)
    r_count = ""
    if status == "OK" and last_file is not None:
        r = subprocess.run(["java", "-cp", str(ROOT / "target" / "classes"), "welt.runner.ResumMain", "import",
                            str(dataset), str(last_file), str(min_sup), repr(min_weight), str(patterns)],
                           capture_output=True, text=True)
        print(r.stdout.strip())
        if r.returncode != 0:
            fail(f"import of the ReSuM result set failed (exit {r.returncode}):\n{r.stdout}{r.stderr}")
        r_count = counts[-1]
    for w in works:
        shutil.rmtree(w, ignore_errors=True)
    row = [dataset.name, "ReSuM-ALL", min_sup, f"{min_weight:.3f}", relevance, limit_ms, warmup,
           len(resum_ms), status, non_ok, r_count, det, *stats(resum_ms), *stats(wall_ms),
           os.environ.get("JAVA_OPTS", ""), commit, sha256(graph), sha256(weights)]
    append_row(out, row)
    print(f"[resum] {dataset.name} minSup={min_sup} tau_w={min_weight}: {status}")


if __name__ == "__main__":
    main()
