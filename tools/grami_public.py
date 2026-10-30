#!/usr/bin/env python3
"""Run the public GraMi (Elseidy et al., PVLDB 2014) on the graphs of the experiments (standard library only).

    python tools/grami_public.py build <work dir>
    python tools/grami_public.py run <work dir> <out.csv> <dataset> <minSup> <limitMs> [warmup] [measured]
    python tools/grami_public.py compare <work dir> <dataset> <minSup> <Output.txt>

build   clones https://github.com/ehab-abdelhamid/GraMi at commit COMMIT into <work dir>/GraMi, copies the
        sources of undirected subgraph mining (GRAMI_UNDIRECTED_SUBGRAPHS/src) to <work dir>/src, removes
        the two imports of javax.jws.Oneway and javax.rmi.CORBA.Util from search/RightMostExtension.java
        (both unused; the two packages left the JDK in Java 11, so the file does not compile otherwise),
        compiles to <work dir>/bin, checks that no compiled class refers to javax/jws or javax/rmi, writes
        <work dir>/input/<dataset>_unlabeled.lg for citeseer and mico (every edge label 0, as in the
        oracle of GraMiOracleTest) and writes <work dir>/build_info.json.
run     the protocol of the experiments, one JVM per run: a probe run that is also the first warm-up (a
        probe that reaches the limit is reported alone), warmup - 1 more warm-ups, then measured runs
        (defaults 1 and 5). Each run is `java -cp bin Dijkstra.main freq=<minSup> ... type=0` in its own
        directory; GraMi writes Output.txt there (its time in seconds, from before reading the graph to
        the end of the search; its pattern count; the patterns). The row records the median, minimum,
        maximum and sample standard deviation of that time and of the wall time of the JVM, the count,
        and the status (OK, TO, ERROR). The Output.txt of the last run is kept as
        <out dir>/grami_output/<dataset>_<minSup>.txt. A cell with a row in <out.csv> is skipped.
compare compiles tools/grami_public/ComparePublicGraMi.java against target/classes and compares an
        Output.txt of GraMi with GraMiStrategy on datasets/<dataset>.lg at the same support (exit 0 equal,
        1 differ, 2 an output whose count line disagrees with its patterns).

JAVA_OPTS is passed to every JVM. Exits 1 on any failed check.
"""
import csv
import hashlib
import json
import os
import shutil
import statistics
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPO = "https://github.com/ehab-abdelhamid/GraMi.git"
COMMIT = "d2875ae80afa2f141323a3f6b89a387787fcda38"
UNUSED_IMPORTS = ("import javax.jws.Oneway;", "import javax.rmi.CORBA.Util;")
DATASETS = ("citeseer", "mico")
FIELDS = ["dataset", "minSup", "limitMs", "runs", "status", "patterns", "graMiSec", "graMiMinSec", "graMiMaxSec",
          "graMiSdSec", "wallMs", "wallMinMs", "wallMaxMs", "wallSdMs", "commit", "javaOpts"]


def java_opts():
    return os.environ.get("JAVA_OPTS", "").split()


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def build(work):
    work = Path(work).resolve()
    clone = work / "GraMi"
    if not clone.exists():
        subprocess.run(["git", "clone", "-q", REPO, clone.as_posix()], check=True)
    subprocess.run(["git", "-C", clone.as_posix(), "checkout", "-q", COMMIT], check=True)
    head = subprocess.run(["git", "-C", clone.as_posix(), "rev-parse", "HEAD"], capture_output=True, text=True,
                          check=True).stdout.strip()
    if head != COMMIT:
        sys.exit(f"ERROR: {clone.as_posix()} is at {head}, expected {COMMIT}")
    src = work / "src"
    if src.exists():
        shutil.rmtree(src)
    shutil.copytree(clone / "GRAMI_UNDIRECTED_SUBGRAPHS" / "src", src)
    f = src / "search" / "RightMostExtension.java"
    lines = f.read_text(encoding="utf-8").splitlines(keepends=True)
    kept = [x for x in lines if x.strip() not in UNUSED_IMPORTS]
    if len(lines) - len(kept) != len(UNUSED_IMPORTS):
        sys.exit(f"ERROR: {f.as_posix()}: removed {len(lines) - len(kept)} import lines, expected {len(UNUSED_IMPORTS)}")
    f.write_text("".join(kept), encoding="utf-8")
    bin_ = work / "bin"
    if bin_.exists():
        shutil.rmtree(bin_)
    bin_.mkdir()
    sources = sorted(p.as_posix() for p in src.rglob("*.java"))
    subprocess.run(["javac", "-nowarn", "-encoding", "UTF-8", "-d", bin_.as_posix()] + sources, check=True)
    classes = sorted(bin_.rglob("*.class"))
    refs = [c.relative_to(bin_).as_posix() for c in classes
            if b"javax/jws" in c.read_bytes() or b"javax/rmi" in c.read_bytes()]
    if refs:
        sys.exit(f"ERROR: compiled classes refer to javax/jws or javax/rmi: {refs}")
    inp = work / "input"
    inp.mkdir(exist_ok=True)
    inputs = {}
    for d in DATASETS:
        src_graph = ROOT / "datasets" / f"{d}.lg"
        if not src_graph.exists():
            sys.exit(f"ERROR: {src_graph.as_posix()} is missing; run tools/fetch_datasets.py {d}.lg")
        out = inp / f"{d}_unlabeled.lg"
        with open(src_graph, encoding="utf-8") as fi, open(out, "w", encoding="utf-8", newline="\n") as fo:
            for line in fi:
                t = line.split()
                fo.write(f"e {t[1]} {t[2]} 0\n" if t and t[0] == "e" else line)
        inputs[d] = {"graph_sha256": sha256(src_graph), "unlabeled_sha256": sha256(out)}
    jv = subprocess.run(["java", "-version"], capture_output=True, text=True).stderr.splitlines()[0]
    info = {"repository": REPO, "commit": head, "removed_lines": list(UNUSED_IMPORTS), "sources": len(sources),
            "classes": len(classes), "java": jv, "inputs": inputs}
    (work / "build_info.json").write_text(json.dumps(info, indent=1) + "\n", encoding="utf-8")
    print(f"grami_public: built {len(classes)} classes from {len(sources)} sources at {head[:7]}; {jv}")


def one_run(work, ds, minsup, limit_ms, rundir):
    if rundir.exists():
        shutil.rmtree(rundir)
    rundir.mkdir(parents=True)
    cmd = ["java"] + java_opts() + ["-cp", (work / "bin").as_posix(), "Dijkstra.main", f"freq={minsup}",
                                    f"filename={ds}_unlabeled.lg", f"datasetFolder={(work / 'input').as_posix()}/",
                                    "distance=1", "type=0", "mlabels=false", "maxLabelAppearance=-1",
                                    "approximate=1", "approxConst=0"]
    t0 = time.perf_counter()
    try:
        p = subprocess.run(cmd, cwd=rundir.as_posix(), capture_output=True, text=True, timeout=limit_ms / 1000.0)
    except subprocess.TimeoutExpired:
        return {"status": "TO", "wall": (time.perf_counter() - t0) * 1000.0}
    wall = (time.perf_counter() - t0) * 1000.0
    (rundir / "stdout.txt").write_text(p.stdout + p.stderr, encoding="utf-8")
    out = rundir / "Output.txt"
    if p.returncode != 0 or not out.exists():
        return {"status": "ERROR", "wall": wall}
    lines = out.read_text(encoding="utf-8").splitlines()
    return {"status": "OK", "wall": wall, "sec": float(lines[0]), "patterns": int(lines[1]), "out": out}


def run(work, out_csv, ds, minsup, limit_ms, warmup=1, measured=5):
    work, out_csv = Path(work).resolve(), Path(out_csv).resolve()
    if ds not in DATASETS:
        sys.exit(f"ERROR: dataset {ds}; known: {DATASETS}")
    if out_csv.exists():
        with open(out_csv, newline="", encoding="utf-8") as fh:
            if any(r["dataset"] == ds and int(r["minSup"]) == minsup for r in csv.DictReader(fh)):
                print(f"--- skip (already in {out_csv.name}): {ds} {minsup}")
                return
    info = json.loads((work / "build_info.json").read_text(encoding="utf-8"))
    rundir = work / "runs" / f"{ds}_{minsup}"
    print(f">>> public GraMi {ds} minSup={minsup} limit={limit_ms}ms")
    probe = one_run(work, ds, minsup, limit_ms, rundir)
    runs = [probe]
    if probe["status"] == "OK":
        for _ in range(warmup - 1):
            one_run(work, ds, minsup, limit_ms, rundir)
        runs = [one_run(work, ds, minsup, limit_ms, rundir) for _ in range(measured)]
    status = next((r["status"] for r in runs if r["status"] != "OK"), "OK")
    row = {k: "" for k in FIELDS}
    row.update(dataset=ds, minSup=minsup, limitMs=limit_ms, runs=len(runs), status=status, commit=info["commit"][:7],
               javaOpts=" ".join(java_opts()))
    walls = [r["wall"] for r in runs]
    row.update(wallMs=f"{statistics.median(walls):.3f}", wallMinMs=f"{min(walls):.3f}", wallMaxMs=f"{max(walls):.3f}",
               wallSdMs=f"{statistics.stdev(walls):.3f}" if len(walls) > 1 else "0")
    if status == "OK":
        secs, counts = [r["sec"] for r in runs], {r["patterns"] for r in runs}
        if len(counts) != 1:
            sys.exit(f"ERROR: {ds} {minsup}: pattern counts differ across runs {sorted(counts)}")
        row.update(patterns=counts.pop(), graMiSec=f"{statistics.median(secs):.4f}", graMiMinSec=f"{min(secs):.4f}",
                   graMiMaxSec=f"{max(secs):.4f}", graMiSdSec=f"{statistics.stdev(secs):.4f}" if len(secs) > 1 else "0")
        keep = out_csv.parent / "grami_output"
        keep.mkdir(exist_ok=True)
        shutil.copyfile(runs[-1]["out"], keep / f"{ds}_{minsup}.txt")
    new = not out_csv.exists()
    with open(out_csv, "a", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=FIELDS)
        if new:
            w.writeheader()
        w.writerow(row)
    print(f"    status {status}, patterns {row['patterns'] or '-'}, GraMi time {row['graMiSec'] or '-'} s, wall {row['wallMs']} ms")


def compare(work, ds, minsup, output):
    out = Path(work).resolve() / "compare"
    out.mkdir(parents=True, exist_ok=True)
    cp = os.pathsep.join([(ROOT / "target" / "classes").as_posix(), out.as_posix()])
    subprocess.run(["javac", "-nowarn", "-cp", cp, "-d", out.as_posix(),
                    (ROOT / "tools" / "grami_public" / "ComparePublicGraMi.java").as_posix()], check=True)
    if not Path(output).is_file():
        sys.exit(f"ERROR: {Path(output).as_posix()} does not exist")
    r = subprocess.run(["java"] + java_opts() + ["-cp", cp, "ComparePublicGraMi", (ROOT / "datasets" / f"{ds}.lg").as_posix(),
                        str(minsup), Path(output).resolve().as_posix()])
    sys.exit(r.returncode)


def main():
    a = sys.argv[1:]
    if len(a) >= 2 and a[0] == "build":
        build(a[1])
    elif len(a) >= 6 and a[0] == "run":
        run(a[1], a[2], a[3], int(a[4]), int(a[5]), *(int(x) for x in a[6:8]))
    elif len(a) == 5 and a[0] == "compare":
        compare(a[1], a[2], int(a[3]), a[4])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
