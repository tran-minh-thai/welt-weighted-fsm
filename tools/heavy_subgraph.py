#!/usr/bin/env python3
"""Write G_{>=tau_w}: the graph of a dataset restricted to the edges weighing at least tau_w (standard library only).

Usage:  python tools/heavy_subgraph.py <dataset.lg> <tau_w> <out.lg>

The graph is the one the miners read (LgGraphReader, duplicate directed lines merged), exported by
ExportResumMain; every vertex is kept, every edge with weight >= tau_w is kept with its weight
written exactly. Mining <out.lg> with GraMi at minSup gives the patterns whose MNI over the
occurrences with all edges >= tau_w reaches minSup, i.e. the ALL score of ReSuM computed exactly.
An existing <out.lg> is reused when its sidecar <out.lg>.source records the same dataset SHA-256
and tau_w. Prints the vertex and edge counts.
"""
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    dataset, tau_text, out = Path(sys.argv[1]), sys.argv[2], Path(sys.argv[3])
    tau = float(tau_text)
    stamp = f"{sha256(dataset)} {tau!r}\n"
    side = Path(str(out) + ".source")
    if out.exists() and side.exists() and side.read_text(encoding="utf-8") == stamp:
        print(f"heavy_subgraph: {out.as_posix()} is current")
        return
    work = Path(tempfile.mkdtemp(prefix="heavy_"))
    try:
        r = subprocess.run(["java", "-cp", str(ROOT / "target" / "classes"), "welt.runner.ExportResumMain",
                            str(dataset), str(work)], capture_output=True, text=True)
        if r.returncode != 0:
            sys.exit(f"ERROR: export of {dataset} failed (exit {r.returncode}):\n{r.stdout}{r.stderr}")
        name = dataset.name[:-3] if dataset.name.endswith(".lg") else dataset.name
        lines = (work / f"{name}.lg").read_text(encoding="utf-8").splitlines()
        weights = (work / f"{name}.w").read_text(encoding="utf-8").split()
    finally:
        shutil.rmtree(work, ignore_errors=True)
    vertices = [l for l in lines if l.startswith("v ")]
    edges = [l.split() for l in lines if l.startswith("e ")]
    if len(edges) != len(weights):
        sys.exit(f"ERROR: {len(edges)} edge lines but {len(weights)} weights in the export of {dataset}")
    if any(e[3] != "0" for e in edges):
        sys.exit(f"ERROR: {dataset} has edge labels; the .lg format written here carries weights only")
    kept = [(e[1], e[2], float(w)) for e, w in zip(edges, weights) if float(w) >= tau]
    out.parent.mkdir(parents=True, exist_ok=True)
    side.unlink(missing_ok=True)
    with open(out, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("t # 1\n")
        fh.write("".join(v + "\n" for v in vertices))
        fh.write("".join(f"e {u} {v} {w!r}\n" for u, v, w in kept))
    side.write_text(stamp, encoding="utf-8")
    print(f"heavy_subgraph: {out.as_posix()}: {len(vertices)} vertices, {len(kept)} of {len(edges)} edges with weight >= {tau!r}")


if __name__ == "__main__":
    main()
