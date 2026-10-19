#!/usr/bin/env python3
"""Self-test of the Python tools on constructed inputs (standard library only).

Usage:  python tools/selftest_tools.py      # exit 0 when every case passes
Covers every branch of choose_voting.py and b5_choose.py and the exit codes of compare_patterns.py.
"""
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
HEADER = "dataset,algorithm,minSup,minWeight,status,voter,backtrack\n"


def run(tool, *args):
    return subprocess.run([sys.executable, os.path.join(HERE, tool), *args], capture_output=True, text=True)


def choose(rows, tmp):
    dev, cal, out = (os.path.join(tmp, n) for n in ("dev.csv", "cal.txt", "out.json"))
    with open(dev, "w", encoding="utf-8") as fh:
        fh.write(HEADER + "".join(rows))
    with open(cal, "w", encoding="utf-8") as fh:
        fh.write("calibration note\n")
    p = run("choose_voting.py", dev, cal, out)
    return p.returncode, (json.load(open(out)) if p.returncode == 0 else None)


def cells(voter, b1, b2, s1="OK", s2="OK"):
    return [f"b.lg,WeLT,1100,12,{s1},{voter},{b1}\n", f"b.lg,WeLT,1050,12,{s2},{voter},{b2}\n"]


def main():
    failures = []

    cases = []

    def check(name, cond):
        cases.append(name)
        print(("ok   " if cond else "FAIL ") + name)
        if not cond:
            failures.append(name)

    with tempfile.TemporaryDirectory() as tmp:
        code, rec = choose(cells("0;1;0", 100, 100) + cells("1;0;0", 80, 80), tmp)
        check("clear winner is chosen", code == 0 and rec["voter"] == "1,0,0")
        code, rec = choose(cells("0;1;0", 100, 100) + cells("1;0;0", 99, 100), tmp)
        check("gain below 2% falls back to degree-only", code == 0 and rec["voter"] == "0,1,0"
              and rec["bestVoter"] == "1,0,0")
        code, rec = choose(cells("0;1;0", 100, 100) + cells("1;0;0", 10, 10, s2="TO"), tmp)
        check("a voter that did not complete every cell does not qualify", code == 0 and rec["voter"] == "0,1,0"
              and "1,0,0" not in rec["qualified"])
        code, rec = choose(cells("0;1;0", 100, 100) + cells("0.2;0.8;0", 50, 50) + cells("0.4;0.6;0", 50, 50), tmp)
        check("tie goes to the first voter in grid order", code == 0 and rec["voter"] == "0.2,0.8,0")
        code, _ = choose(cells("1;0;0", 50, 50), tmp)
        check("missing degree-only voter is an error", code == 1)

        pdir = os.path.join(tmp, "patterns")
        os.makedirs(pdir)
        head = "code\tvertices\tedges\tweight\n"
        for algo, codes in (("EdgeTypeBound", ["a", "b"]), ("WeLT", ["a", "b"]), ("GraMi", ["a", "b", "c"])):
            with open(os.path.join(pdir, f"x_{algo}_10_5.0.tsv"), "w", encoding="utf-8") as fh:
                fh.write(head + "".join(f"{c}\t2\t1\t5.0\n" for c in codes))
        out = os.path.join(tmp, "cmp.csv")
        check("compare: identical sets exit 0", run("compare_patterns.py", pdir, out).returncode == 0)
        check("compare: different sets exit 1", run("compare_patterns.py", pdir, out, "GraMi", "WeLT").returncode == 1)
        check("compare: nothing comparable exits 2", run("compare_patterns.py", pdir, out, "WEGM", "WeLT").returncode == 2)
        check("compare --subset: contained set exits 0",
              run("compare_patterns.py", "--subset", pdir, out, "WeLT", "GraMi").returncode == 0)
        check("compare --subset: larger set exits 1",
              run("compare_patterns.py", "--subset", pdir, out, "GraMi", "WeLT").returncode == 1)

        b1 = os.path.join(tmp, "b1.csv")
        with open(b1, "w", encoding="utf-8") as fh:
            fh.write("dataset,algorithm,minSup,status\n"
                     "c.lg,GraMi,400,OK\nc.lg,GraMi,250,OK\nc.lg,GraMi,200,TO\nc.lg,WeLT,100,OK\n"
                     "l.lg,GraMi,600,TO\nl.lg,GraMi,200,OOM\nc.lg,GraMi,300,CRASH\nc.lg,GraMi,300,OK\n")
        out = lambda *x: run("b5_choose.py", *x).stdout.strip()
        check("b5: smallest completed support, timeouts ignored", out("smallest-ok", b1, "c.lg", "GraMi") == "250")
        check("b5: no completed support gives nothing", out("smallest-ok", b1, "l.lg", "GraMi") == "")
        check("b5: status is the last row of the cell", out("status", b1, "c.lg", "GraMi", "300") == "OK")
        check("b5: a missing cell is none", out("status", b1, "c.lg", "GraMi", "150") == "none")
        b1t = os.path.join(tmp, "b1t.csv")
        with open(b1t, "w", encoding="utf-8") as fh:
            fh.write("dataset,algorithm,minSup,status,totalMs\n"
                     "c.lg,WeLT,300,OK,10\nc.lg,WeLT,200,OK,59000\nc.lg,WeLT,150,OK,61000\nc.lg,WeLT,100,TO,3600000\n")
        check("hard: smallest completed support within 60 s", out("smallest-ok-within", b1t, "c.lg", "WeLT", "60000") == "200")
        check("hard: nothing within the cap gives nothing", out("smallest-ok-within", b1t, "c.lg", "WeLT", "5") == "")

    print(f"selftest_tools: {len(cases) - len(failures)}/{len(cases)} cases passed")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
