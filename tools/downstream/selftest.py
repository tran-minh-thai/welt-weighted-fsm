#!/usr/bin/env python3
"""Self-test of the downstream experiment tools: every check is also run on an input built to
make it fail, and must fail there.

Usage:  python tools/downstream/selftest.py      # exit 0 when every case passes
"""
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)
import b6lib  # noqa: E402

CASES = []


def case(name):
    def deco(f):
        CASES.append((name, f))
        return f
    return deco


def raises(f, exc=ValueError):
    try:
        f()
    except exc:
        return True
    return False


# ---------------------------------------------------------------- graph and leakage

@case("degree classes: quintile cuts with ties, all-equal degrees, single class sizes")
def _():
    # path 0-1-2-3-4-5: degrees 1,2,2,2,2,1 -> sorted 1,1,2,2,2,2; cuts at n=6: s[1],s[2],s[3],s[4] = 1,2,2,2
    path = [(i, i + 1) for i in range(5)]
    assert b6lib.degree_classes(6, path) == [0, 1, 1, 1, 1, 0]
    cycle = [(i, (i + 1) % 5) for i in range(5)]
    assert b6lib.degree_classes(5, cycle) == [0] * 5, "equal degrees share one class"
    star = [(0, i) for i in range(1, 6)]
    assert b6lib.degree_classes(6, star) == [4, 0, 0, 0, 0, 0], "cuts 1,1,1,1: the hub is above all four"


def toy_graph():
    labels = [0, 1, 1, 1, 1, 0]
    edges = [(i, i + 1, float(10 * i)) for i in range(5)]
    return labels, edges


@case("leakage report: accepts the structural graph")
def _():
    s_labels, edges = toy_graph()
    ok, facts = b6lib.leakage_report(s_labels, edges, [5, 4, 3, 2, 1, 0], list(edges), [2, 2, 3, 3, 4, 4])
    assert ok and facts["edges_equal"] and facts["labels_are_degree_classes"], facts


@case("leakage report: rejects labels equal to the class target")
def _():
    s_labels, edges = toy_graph()
    ok, _ = b6lib.leakage_report(s_labels, edges, s_labels, list(edges), list(s_labels))
    assert not ok


@case("leakage report: rejects one changed edge weight")
def _():
    s_labels, edges = toy_graph()
    ref = list(edges)
    ref[2] = (ref[2][0], ref[2][1], ref[2][2] + 0.0001)
    ok, facts = b6lib.leakage_report(s_labels, edges, s_labels, ref, [9] * 6)
    assert not ok and not facts["edges_equal"]


@case("leakage report: rejects labels that are not the degree classes")
def _():
    s_labels, edges = toy_graph()
    ok, facts = b6lib.leakage_report([1, 0, 1, 1, 1, 0], edges, s_labels, list(edges), [9] * 6)
    assert not ok and not facts["labels_are_degree_classes"]


def converter(tmp, ref_lines):
    edges = os.path.join(tmp, "edges.csv")
    feats = os.path.join(tmp, "features.json")
    with open(edges, "w") as f:
        f.write("node_1,node_2\n0,1\n1,2\n2,3\n")
    with open(feats, "w") as f:
        f.write('{"0": [1, 2], "1": [2, 3], "2": [3], "3": []}')
    ref = os.path.join(tmp, "ref.lg")
    with open(ref, "w", newline="\n") as f:
        f.write("".join(line + "\n" for line in ref_lines))
    out = os.path.join(tmp, "out.lg")
    p = subprocess.run([sys.executable, os.path.join(ROOT, "datasets", "convert_musae_struct.py"),
                        edges, feats, out, "--check-against", ref], capture_output=True, text=True)
    return p.returncode, out


REF = ["t # 1", "v 0 7", "v 1 7", "v 2 8", "v 3 8",
       "e 0 1 33.3333", "e 1 2 50.0000", "e 2 3 0.0000"]


@case("converter check: identical edges pass, labels are the degree classes")
def _():
    with tempfile.TemporaryDirectory() as tmp:
        code, out = converter(tmp, REF)
        assert code == 0, code
        labels, _ = b6lib.read_lg(out)
        assert labels == [0, 2, 2, 0], labels  # degrees 1,2,2,1: cuts 1,1,2,2


@case("converter check: one changed weight is caught")
def _():
    with tempfile.TemporaryDirectory() as tmp:
        bad = list(REF)
        bad[6] = "e 1 2 50.0001"
        assert converter(tmp, bad)[0] == 1


@case("converter check: a missing vertex is caught")
def _():
    with tempfile.TemporaryDirectory() as tmp:
        assert converter(tmp, [x for x in REF if x != "v 3 8"])[0] == 1


# ---------------------------------------------------------------- selection

def fake_scan(results, order=None):
    """Runs b6lib.scan with a table {(tau_s, tau_w): (status, R)}; returns (chosen record, reason, records, calls)."""
    calls = []
    order = order or [(1600, 0.5, "a"), (1600, 0.7, "b"), (1200, 0.5, "a"), (1200, 0.7, "b"),
                      (800, 0.5, "a"), (800, 0.7, "b")]

    def run(s, tw):
        calls.append((s, tw))
        return results[(s, tw)]
    idx, reason, recs = b6lib.scan(order, run)
    return (None if idx is None else recs[idx]), reason, recs, calls


@case("selection: window bounds 20 and 300 are inclusive, 19 and 301 are not")
def _():
    lo, hi = b6lib.PATTERN_WINDOW
    assert (lo, hi) == (20, 300)
    c, _, _, _ = fake_scan({(1600, "a"): ("OK", 19), (1600, "b"): ("OK", 20)})
    assert (c["tau_s"], c["rho_w"]) == (1600, 0.7), c
    c, _, _, _ = fake_scan({(1600, "a"): ("OK", 301), (1600, "b"): ("OK", 300)})
    assert (c["tau_s"], c["rho_w"]) == (1600, 0.7), c


@case("selection: above the window skips the lower tau_s of that rho_w without running them")
def _():
    c, _, recs, calls = fake_scan({(1600, "a"): ("OK", 0), (1600, "b"): ("OK", 0), (1200, "a"): ("OK", 400),
                                   (1200, "b"): ("OK", 5), (800, "b"): ("OK", 25)})
    assert (800, "a") not in calls and recs[4]["status"] == "SKIPPED", recs
    assert (c["tau_s"], c["rho_w"]) == (800, 0.7), c


@case("selection: an incomplete run above the window decides, below it stops the scan")
def _():
    c, _, recs, calls = fake_scan({(1600, "a"): ("TO", 2374), (1600, "b"): ("OK", 40)})
    assert recs[0]["decision"].startswith("above") and (c["tau_s"], c["rho_w"]) == (1600, 0.7)
    c, reason, recs, calls = fake_scan({(1600, "a"): ("OK", 5), (1600, "b"): ("TO", 120)})
    assert c is None and "TO" in reason and len(calls) == 2, (reason, calls)
    c, reason, recs, calls = fake_scan({(1600, "a"): ("OOM", 10)})
    assert c is None and len(calls) == 1


@case("selection: every rho_w above the window, or a grid without a window cell, gives no choice")
def _():
    c, reason, _, calls = fake_scan({(1600, "a"): ("OK", 500), (1600, "b"): ("TO", 301)})
    assert c is None and len(calls) == 2 and "every rho_w" in reason, reason
    c, reason, recs, _ = fake_scan({k: ("OK", 3) for k in [(s, t) for s in (1600, 1200, 800) for t in "ab"]})
    assert c is None and "no cell" in reason and len(recs) == 6


@case("selection: scan order is tau_s descending, then rho_w 0.5 before 0.7")
def _():
    order = b6lib.selection_order({0.5: "a", 0.7: "b"})
    assert order[:3] == [(1600, 0.5, "a"), (1600, 0.7, "b"), (1200, 0.5, "a")]
    assert len(order) == 2 * len(b6lib.TAU_S_GRID)
    assert list(b6lib.TAU_S_GRID) == sorted(b6lib.TAU_S_GRID, reverse=True)


# ---------------------------------------------------------------- features

@case("image sets: matrix entries are exactly the images; bad lines are rejected")
def _():
    with tempfile.TemporaryDirectory() as tmp:
        p = os.path.join(tmp, "img.tsv")
        with open(p, "w") as f:
            f.write("pattern\tvertex\tcode\tsize\timages\n0\t0\tc\t2\t1 3\n0\t1\tc\t1\t2\n1\t0\td\t2\t1 3\n")
        sets = b6lib.read_image_sets(p)
        m = b6lib.pattern_matrix(4, sets).toarray()
        assert m.tolist() == [[0, 0, 0], [1, 0, 1], [0, 1, 0], [1, 0, 1]], m.tolist()
        assert b6lib.distinct_columns(b6lib.pattern_matrix(4, sets)) == 2
        assert raises(lambda: b6lib.pattern_matrix(3, sets)), "vertex id 3 is out of range for n=3"
        with open(p, "w") as f:
            f.write("pattern\tvertex\tcode\tsize\timages\n0\t0\tc\t3\t1 3\n")
        assert raises(lambda: b6lib.read_image_sets(p)), "size 3 with 2 ids"
        with open(p, "w") as f:
            f.write("pattern\tvertex\tcode\tsize\timages\n0\t0\tc\t0\t\n")
        assert raises(lambda: b6lib.read_image_sets(p)), "empty image set"


@case("baseline features: binary columns, degree and incident weight")
def _():
    with tempfile.TemporaryDirectory() as tmp:
        p = os.path.join(tmp, "f.json")
        with open(p, "w") as f:
            f.write('{"0": [5, 9], "1": [9], "2": []}')
        m, nb = b6lib.baseline_matrix(p, 3, [(0, 1, 2.0), (1, 2, 3.0)])
        assert nb == 2
        assert m.toarray().tolist() == [[1, 1, 1, 2], [0, 1, 2, 5], [0, 0, 1, 3]], m.toarray().tolist()


# ---------------------------------------------------------------- evaluation

@case("evaluation: an informative feature beats noise; folds are paired; Wilcoxon reports ties")
def _():
    import numpy as np
    from scipy import sparse
    rng = np.random.default_rng(0)
    y = np.repeat([0, 1, 2], 40)
    informative = sparse.csr_matrix(np.eye(3)[y] + 0.1 * rng.random((120, 3)))
    noise = sparse.csr_matrix(rng.random((120, 3)))
    rows = b6lib.evaluate({"baseline": noise, "baseline+patterns": informative}, y, seeds=(0,), folds=4,
                          log=lambda m: None)
    s = b6lib.summarise(rows)
    assert s["per_set"]["baseline+patterns"]["macro_f1_mean"] > 0.95, s
    assert s["per_set"]["baseline"]["macro_f1_mean"] < 0.6, s
    assert s["wilcoxon"]["macro_f1"]["pairs"] == 4
    same = b6lib.summarise([dict(r, features=f) for r in rows if r["features"] == "baseline"
                            for f in ("baseline", "baseline+patterns")])
    assert same["wilcoxon"]["macro_f1"]["zero_differences"] == 4 and same["wilcoxon"]["macro_f1"]["p_value"] is None
    unpaired = [r for r in rows if not (r["features"] == "baseline" and r["fold"] == 0)]
    assert raises(lambda: b6lib.summarise(unpaired)), "missing fold must be refused"


def main():
    failures = []
    for name, f in CASES:
        try:
            f()
            print(f"  ok    {name}")
        except Exception as e:  # an exception other than a failed assertion is a failure too
            failures.append(name)
            print(f"  FAIL  {name}: {type(e).__name__}: {e}")
    print(f"downstream selftest: {len(CASES) - len(failures)}/{len(CASES)} cases passed")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
