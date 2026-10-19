#!/usr/bin/env python3
"""Fetch and compile ReSuM (Preti et al.; github.com/lady-bluecopper/ReSuM, GPL-3.0) for the
comparison with its ALL score. Standard library only; runs on Windows and macOS.

Usage:  python tools/build_resum.py [--dest DIR]      (default DIR: third_party/resum)

The source is cloned at a fixed commit and compiled with the local javac (the undirected
variant, ReSuM-UND); its six runtime libraries are downloaded from Maven Central and checked
against SHA-256 values. Nothing of ReSuM enters this repository: DIR is ignored by git.
Writes DIR/build_info.json and prints the classpath. A second call with an unchanged
build_info.json does nothing.
"""
import argparse
import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

REPO = "https://github.com/lady-bluecopper/ReSuM.git"
COMMIT = "38307b26df471d261660e4b652782483163f748b"
MAIN_CLASS = "sa.edu.kaust.grami.und.main.Main"
CENTRAL = "https://repo1.maven.org/maven2/"
JARS = [
    ("com/koloboke/koloboke-api-jdk8/1.0.0/koloboke-api-jdk8-1.0.0.jar",
     "536364887d3034844f72da64e2585953dc4110a4677713ef873944bc86f065c7"),
    ("com/koloboke/koloboke-impl-common-jdk8/1.0.0/koloboke-impl-common-jdk8-1.0.0.jar",
     "adc525e7734a6da0a5719e6e3d53d73400a6117af07817e06a4d580c9472242d"),
    ("com/koloboke/koloboke-impl-jdk8/1.0.0/koloboke-impl-jdk8-1.0.0.jar",
     "dd16ed6b0c90aa4ac99b93008f500fad945e5a74cdaf4e32cadc1202a3307248"),
    ("com/google/guava/guava/19.0/guava-19.0.jar",
     "58d4cc2e05ebb012bbac568b032f75623be1cb6fb096f3c60c72a86f7f057de4"),
    ("info/debatty/java-string-similarity/0.12/java-string-similarity-0.12.jar",
     "2c288063fb654795b956486ff159b5dee8daf0efd1f359f395b8085034032695"),
    ("org/apache/commons/commons-collections4/4.1/commons-collections4-4.1.jar",
     "b1fe8b5968b57d8465425357ed2d9dc695504518bed2df5b565c4b8e68c1c8a5"),
]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def run(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        sys.exit(f"ERROR: {' '.join(cmd)} exited {r.returncode}\n{r.stdout}{r.stderr}")
    return r.stdout


def fetch_jar(rel, digest, lib):
    out = lib / rel.rsplit("/", 1)[1]
    if out.exists() and sha256(out) == digest:
        return out
    for attempt in range(1, 6):
        try:
            with urllib.request.urlopen(CENTRAL + rel, timeout=120) as r, open(out, "wb") as fh:
                fh.write(r.read())
            break
        except OSError as e:
            print(f"download {rel} failed (attempt {attempt}/5): {e}", file=sys.stderr)
            time.sleep(5 * attempt)
    else:
        sys.exit(f"ERROR: could not download {CENTRAL + rel}")
    got = sha256(out)
    if got != digest:
        sys.exit(f"ERROR: {out}: SHA-256 {got}, expected {digest}")
    return out


def classpath(dest):
    return os.pathsep.join([str(dest / "classes"), str(dest / "lib" / "*")])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dest", default=str(Path(__file__).resolve().parent.parent / "third_party" / "resum"))
    dest = Path(ap.parse_args().dest).resolve()
    info_file = dest / "build_info.json"
    javac_version = run(["javac", "-version"]).strip() or "unknown"
    if info_file.exists():
        info = json.loads(info_file.read_text(encoding="utf-8"))
        if info.get("commit") == COMMIT and info.get("javac") == javac_version and (dest / "classes").is_dir():
            print(f"ReSuM already built at {dest} (commit {COMMIT[:7]}, {javac_version})")
            print(classpath(dest))
            return
    src = dest / "src"
    if not (src / ".git").is_dir():
        dest.mkdir(parents=True, exist_ok=True)
        run(["git", "clone", "--quiet", REPO, str(src)])
    run(["git", "-C", str(src), "fetch", "--quiet", "origin"])
    run(["git", "-C", str(src), "checkout", "--quiet", "--detach", COMMIT])
    head = run(["git", "-C", str(src), "rev-parse", "HEAD"]).strip()
    if head != COMMIT:
        sys.exit(f"ERROR: {src} is at {head}, expected {COMMIT}")
    dirty = run(["git", "-C", str(src), "status", "--porcelain"]).strip()
    if dirty:
        sys.exit(f"ERROR: {src} has local changes:\n{dirty}")
    lib = dest / "lib"
    lib.mkdir(exist_ok=True)
    jars = {p.rsplit("/", 1)[1]: d for p, d in JARS}
    for rel, digest in JARS:
        fetch_jar(rel, digest, lib)
    extra = sorted(p.name for p in lib.glob("*.jar") if p.name not in jars)
    if extra:
        sys.exit(f"ERROR: unexpected jars in {lib}: {extra}")
    java_root = src / "ReSuM-UND" / "src" / "main" / "java"
    sources = sorted(p.as_posix() for p in java_root.rglob("*.java"))
    classes = dest / "classes"
    if classes.exists():
        for p in sorted(classes.rglob("*"), reverse=True):
            p.unlink() if p.is_file() else p.rmdir()
    classes.mkdir()
    argfile = dest / "sources.txt"
    argfile.write_text("\n".join(f'"{s}"' for s in sources) + "\n", encoding="utf-8")
    run(["javac", "-encoding", "UTF-8", "-nowarn", "-proc:none", "-cp", str(lib / "*"),
         "-d", str(classes), "@" + str(argfile)])
    compiled = sum(1 for _ in classes.rglob("*.class"))
    info = {"repository": REPO, "commit": COMMIT, "variant": "ReSuM-UND", "main_class": MAIN_CLASS,
            "javac": javac_version, "sources": len(sources), "classes": compiled,
            "jars": {name: digest for name, digest in jars.items()}}
    info_file.write_text(json.dumps(info, indent=2) + "\n", encoding="utf-8")
    print(f"ReSuM built at {dest}: {len(sources)} sources, {compiled} classes ({javac_version})")
    print(classpath(dest))


if __name__ == "__main__":
    main()
