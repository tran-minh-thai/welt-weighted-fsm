#!/usr/bin/env python3
"""Fetch, rebuild and verify every dataset of the experiments (standard library only).

Usage:  python tools/fetch_datasets.py [name.lg ...]          # verify; fetch and rebuild what is missing
        python tools/fetch_datasets.py --check [name.lg ...]  # verify only, never download
With names, only those graphs are handled (test.sh fetches the three graphs the tests read).

Committed graphs are only verified. The graphs that are not committed, because their sources state
no licence that allows redistributing them, are rebuilt from their sources:
  citeseer.lg, mico.lg, test1.lg, test2.lg
             downloaded from the GraMi repository (Elseidy et al., PVLDB 2014), Datasets/;
  email_eu.lg
             converted with datasets/convert_email.py from the SNAP files email-Eu-core-temporal.txt.gz
             and email-Eu-core-department-labels.txt.gz, keeping the pairs with at least 25 emails;
  lastfm.lg  converted with datasets/convert_musae.py from the SNAP archive lastfm_asia.zip (MUSAE LastFM Asia);
  bitcoin_otc.lg
             converted with datasets/convert_bitcoin.py from the SNAP file soc-sign-bitcoinotc.csv.gz;
  github.lg  converted with datasets/convert_musae.py from the SNAP archive git_web_ml.zip (MUSAE GitHub).
Every download is checked against its SHA-256 before use, and the conversions are deterministic.
Every file is checked against the SHA-256 below; a mismatch stops with exit code 1, so a run
can never start on different data. Run it from the repository root or from anywhere.
"""
import gzip
import hashlib
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, "datasets")
RAW = os.path.join(DATA, "raw")

# SHA-256 of the graphs used in the experiments (LF line endings, see .gitattributes).
GRAPHS = {
    "bitcoin_otc.lg": "4967da48059fc147ddf24a420fab088c0e4d8cad39a7c0e7674bfced68a6d212",
    "citeseer.lg": "1150fe20d702c49419a5296dc9d8ada47e912a48eda5fdab2d2f987510200a02",
    "email_eu.lg": "5b413cc05e79da1a2ee70837e1541106254bbc00706e2141c3eb65e36f254278",
    "example.lg": "12faa0c703dcccde2979947014e36e441b18822b782293923eca7251a59d774c",
    "github.lg": "4f2e3e63ab3120d3c099606aca2000d1296bc9c4bc49403beb22e57dd892c2a1",
    "lastfm.lg": "be77963234648e22e998b703b2418f9d5dd030926713665fd049e7053c8ed3b7",
    "mico.lg": "f94eabe3659b8b35027323f2df00b749c63c78d1e954d36a4036218001333aa1",
    "string_ecoli.lg": "7a416bbeec1ac4de9cfb42f7fe4041b781cadf36dc29793f170cd7bba6f9d18a",
    "test1.lg": "a51277d382147af197008c31ba9e3bb90b5e467363969fe22b6cd7444dd203d6",
    "test2.lg": "a396aea7b57a46d823afb2d09bfb4fb3f9f69358aa3c1478eb6800c06eeffd95",
}
GRAMI_URL = "https://raw.githubusercontent.com/ehab-abdelhamid/GraMi/master/Datasets/"
GRAMI_FILES = ("citeseer.lg", "mico.lg", "test1.lg", "test2.lg")
MICO_URL = GRAMI_URL + "mico.lg"
SNAP_URL = "https://snap.stanford.edu/data/"
# SNAP sources of the converted graphs: (file, SHA-256)
EMAIL_SOURCES = (("email-Eu-core-temporal.txt.gz", "f6cc93249978e59d1aba111b55ea10ec553917419a3be1210012b5db3580efa6"),
                 ("email-Eu-core-department-labels.txt.gz", "e5abe5b4581a480032a63adcf2576c161785f45692642c6ebb0b1276f0f33669"))
EMAIL_MIN_EMAILS = 25
LASTFM_ZIP = ("lastfm_asia.zip", "51acb78a923bb223ed6e61be88f91122fb29adca3f07beff7289cafd98601d47")
BITCOIN_SOURCE = ("soc-sign-bitcoinotc.csv.gz", "6424ac981dad3a019f697fc1b9fcd85c19d8d9f039797758e9ffb6fea100c373")
GITHUB_ZIP_URL = "https://snap.stanford.edu/data/git_web_ml.zip"
GITHUB_ZIP_SHA = "56c0c1c2c460dc4c7bf89357b1fec020f45e2ecebab81d131d073b10e28ec031"


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def download(url, dest, attempts=5):
    """Download with retries: waits of 10, 20, 40 and 80 s between attempts."""
    tmp = dest + ".part"
    for attempt in range(1, attempts + 1):
        print(f"  downloading {url} (attempt {attempt}/{attempts})")
        try:
            with urllib.request.urlopen(url, timeout=120) as r, open(tmp, "wb") as out:
                while True:
                    block = r.read(1 << 20)
                    if not block:
                        break
                    out.write(block)
            os.replace(tmp, dest)
            return
        except (OSError, urllib.error.URLError) as e:
            print(f"  download failed: {e}")
            if attempt == attempts:
                sys.exit(f"ERROR: could not download {url} after {attempts} attempts")
            time.sleep(10 * 2 ** (attempt - 1))


def source(name, expected):
    """A source file in datasets/raw, downloaded from SNAP when missing, checked against its SHA-256."""
    os.makedirs(RAW, exist_ok=True)
    path = os.path.join(RAW, name)
    if not os.path.exists(path):
        download(SNAP_URL + name, path)
    got = sha256(path)
    if got != expected:
        sys.exit(f"ERROR: {path} has SHA-256 {got}, expected {expected}")
    return path


def gunzip(path):
    out = path[:-len(".gz")]
    with gzip.open(path, "rb") as src, open(out, "wb") as dst:
        shutil.copyfileobj(src, dst)
    return out


def rebuild(name):
    if name in GRAMI_FILES:
        download(GRAMI_URL + name, os.path.join(DATA, name))
    elif name == "email_eu.lg":
        temporal, labels = (gunzip(source(f, h)) for f, h in EMAIL_SOURCES)
        subprocess.run([sys.executable, os.path.join(DATA, "convert_email.py"), temporal, labels,
                        str(EMAIL_MIN_EMAILS), os.path.join(DATA, name)], check=True)
    elif name == "lastfm.lg":
        zpath = source(*LASTFM_ZIP)
        with zipfile.ZipFile(zpath) as z:
            z.extractall(RAW)
        src = os.path.join(RAW, "lasftm_asia")   # the folder name of the SNAP archive
        subprocess.run([sys.executable, os.path.join(DATA, "convert_musae.py"),
                        os.path.join(src, "lastfm_asia_edges.csv"), os.path.join(src, "lastfm_asia_features.json"),
                        os.path.join(src, "lastfm_asia_target.csv"), os.path.join(DATA, name)], check=True)
    elif name == "bitcoin_otc.lg":
        ratings = gunzip(source(*BITCOIN_SOURCE))
        subprocess.run([sys.executable, os.path.join(DATA, "convert_bitcoin.py"), ratings,
                        os.path.join(DATA, name)], check=True)
    elif name == "github.lg":
        os.makedirs(RAW, exist_ok=True)
        zpath = os.path.join(RAW, "git_web_ml.zip")
        if not os.path.exists(zpath):
            download(GITHUB_ZIP_URL, zpath)
        got = sha256(zpath)
        if got != GITHUB_ZIP_SHA:
            sys.exit(f"ERROR: {zpath} has SHA-256 {got}, expected {GITHUB_ZIP_SHA}")
        with zipfile.ZipFile(zpath) as z:
            z.extractall(RAW)
        src = os.path.join(RAW, "git_web_ml")
        subprocess.run([sys.executable, os.path.join(DATA, "convert_musae.py"),
                        os.path.join(src, "musae_git_edges.csv"), os.path.join(src, "musae_git_features.json"),
                        os.path.join(src, "musae_git_target.csv"), os.path.join(DATA, name)], check=True)
    else:
        sys.exit(f"ERROR: {name} is committed to the repository but missing; run git checkout / git pull")


def main():
    check_only = "--check" in sys.argv[1:]
    names = [a for a in sys.argv[1:] if not a.startswith("--")]
    unknown = [n for n in names if n not in GRAPHS]
    if unknown:
        sys.exit(f"ERROR: unknown graph(s) {unknown}; known: {sorted(GRAPHS)}")
    graphs = {n: GRAPHS[n] for n in names} if names else GRAPHS
    bad = 0
    for name, expected in graphs.items():
        path = os.path.join(DATA, name)
        if not os.path.exists(path):
            if check_only:
                print(f"MISSING  {name}")
                bad += 1
                continue
            print(f"missing  {name}: rebuilding")
            rebuild(name)
        got = sha256(path)
        if got == expected:
            print(f"OK       {name}")
        else:
            print(f"MISMATCH {name}: SHA-256 {got}, expected {expected}")
            bad += 1
    print(f"fetch_datasets: {len(graphs) - bad}/{len(graphs)} graphs verified")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
