#!/usr/bin/env python3
"""Fetch, rebuild and verify every dataset of the experiments (standard library only).

Usage:  python tools/fetch_datasets.py            # verify; fetch and rebuild what is missing
        python tools/fetch_datasets.py --check    # verify only, never download

Committed graphs are only verified. The two large graphs that are not committed are rebuilt:
  mico.lg    downloaded from the GraMi repository (Elseidy et al., PVLDB 2014);
  github.lg  converted with datasets/convert_musae.py from the SNAP archive git_web_ml.zip
             (MUSAE GitHub); the conversion is deterministic.
Every file is checked against the SHA-256 below; a mismatch stops with exit code 1, so a run
can never start on different data. Run it from the repository root or from anywhere.
"""
import hashlib
import os
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
    "email_eu_struct.lg": "7a4005c606221f5daac84e57542c38e4b5a7366b7bdc6a3fc97f51c21e059db5",
    "example.lg": "12faa0c703dcccde2979947014e36e441b18822b782293923eca7251a59d774c",
    "github.lg": "4f2e3e63ab3120d3c099606aca2000d1296bc9c4bc49403beb22e57dd892c2a1",
    "github_struct.lg": "05da9f8fb4c22224bf4602a15ea04b7441a89ab9b850875713d063b222a44b46",
    "lastfm.lg": "be77963234648e22e998b703b2418f9d5dd030926713665fd049e7053c8ed3b7",
    "lastfm_struct.lg": "f80c68853ba6bea9f2f133b853ec114c197025b03a4e478741f229fbaec5d44c",
    "mico.lg": "f94eabe3659b8b35027323f2df00b749c63c78d1e954d36a4036218001333aa1",
    "string_ecoli.lg": "7a416bbeec1ac4de9cfb42f7fe4041b781cadf36dc29793f170cd7bba6f9d18a",
    "test1.lg": "a51277d382147af197008c31ba9e3bb90b5e467363969fe22b6cd7444dd203d6",
    "test2.lg": "a396aea7b57a46d823afb2d09bfb4fb3f9f69358aa3c1478eb6800c06eeffd95",
}
MICO_URL = "https://raw.githubusercontent.com/ehab-abdelhamid/GraMi/master/Datasets/mico.lg"
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


def github_raw():
    """The extracted MUSAE GitHub archive (downloaded and SHA-256 checked when missing)."""
    os.makedirs(RAW, exist_ok=True)
    zpath = os.path.join(RAW, "git_web_ml.zip")
    if not os.path.exists(zpath):
        download(GITHUB_ZIP_URL, zpath)
    got = sha256(zpath)
    if got != GITHUB_ZIP_SHA:
        sys.exit(f"ERROR: {zpath} has SHA-256 {got}, expected {GITHUB_ZIP_SHA}")
    with zipfile.ZipFile(zpath) as z:
        z.extractall(RAW)
    return os.path.join(RAW, "git_web_ml")


def rebuild(name):
    if name == "mico.lg":
        download(MICO_URL, os.path.join(DATA, name))
    elif name == "github.lg":
        src = github_raw()
        subprocess.run([sys.executable, os.path.join(DATA, "convert_musae.py"),
                        os.path.join(src, "musae_git_edges.csv"), os.path.join(src, "musae_git_features.json"),
                        os.path.join(src, "musae_git_target.csv"), os.path.join(DATA, name)], check=True)
    elif name == "github_struct.lg":
        # the edges and weights of github.lg with degree-class vertex labels (downstream experiment)
        src = github_raw()
        subprocess.run([sys.executable, os.path.join(DATA, "convert_musae_struct.py"),
                        os.path.join(src, "musae_git_edges.csv"), os.path.join(src, "musae_git_features.json"),
                        os.path.join(DATA, name), "--check-against", os.path.join(DATA, "github.lg")], check=True)
    else:
        sys.exit(f"ERROR: {name} is committed to the repository but missing; run git checkout / git pull")


def main():
    check_only = "--check" in sys.argv[1:]
    bad = 0
    for name, expected in GRAPHS.items():
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
    print(f"fetch_datasets: {len(GRAPHS) - bad}/{len(GRAPHS)} graphs verified")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
