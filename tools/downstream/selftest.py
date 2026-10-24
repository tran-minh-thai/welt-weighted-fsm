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
import lib  # noqa: E402

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
    assert lib.degree_classes(6, path) == [0, 1, 1, 1, 1, 0]
    cycle = [(i, (i + 1) % 5) for i in range(5)]
    assert lib.degree_classes(5, cycle) == [0] * 5, "equal degrees share one class"
    star = [(0, i) for i in range(1, 6)]
    assert lib.degree_classes(6, star) == [4, 0, 0, 0, 0, 0], "cuts 1,1,1,1: the hub is above all four"


def toy_graph():
    labels = [0, 1, 1, 1, 1, 0]
    edges = [(i, i + 1, float(10 * i)) for i in range(5)]
    return labels, edges


@case("leakage report: accepts the structural graph")
def _():
    s_labels, edges = toy_graph()
    ok, facts = lib.leakage_report(s_labels, edges, [5, 4, 3, 2, 1, 0], list(edges), [2, 2, 3, 3, 4, 4])
    assert ok and facts["edges_equal"] and facts["labels_are_degree_classes"], facts


@case("leakage report: rejects labels equal to the class target")
def _():
    s_labels, edges = toy_graph()
    ok, _ = lib.leakage_report(s_labels, edges, s_labels, list(edges), list(s_labels))
    assert not ok


@case("leakage report: rejects one changed edge weight")
def _():
    s_labels, edges = toy_graph()
    ref = list(edges)
    ref[2] = (ref[2][0], ref[2][1], ref[2][2] + 0.0001)
    ok, facts = lib.leakage_report(s_labels, edges, s_labels, ref, [9] * 6)
    assert not ok and not facts["edges_equal"]


@case("leakage report: rejects labels that are not the degree classes")
def _():
    s_labels, edges = toy_graph()
    ok, facts = lib.leakage_report([1, 0, 1, 1, 1, 0], edges, s_labels, list(edges), [9] * 6)
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
        labels, _ = lib.read_lg(out)
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

def fake_scan(results, order=None, check=None):
    """Runs lib.scan with a table {(tau_s, tau_w): (status, R)}; returns (chosen record, reason, records, calls)."""
    calls = []
    order = order or [(1600, 0.5, "a"), (1600, 0.7, "b"), (1200, 0.5, "a"), (1200, 0.7, "b"),
                      (800, 0.5, "a"), (800, 0.7, "b")]

    def run(s, tw):
        calls.append((s, tw))
        return results[(s, tw)]
    idx, reason, recs = lib.scan(order, run, check)
    return (None if idx is None else recs[idx]), reason, recs, calls


@case("selection: window bounds 20 and 300 are inclusive, 19 and 301 are not")
def _():
    lo, hi = lib.PATTERN_WINDOW
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


@case("selection: the second condition is tested only in the window; a failed cell does not stop the scan")
def _():
    checked = []

    def check(s, tw):
        checked.append((s, tw))
        return (s != 1200), {"status": "OK" if s != 1200 else "TO"}
    table = {(1600, "a"): ("OK", 5), (1600, "b"): ("OK", 400), (1200, "a"): ("OK", 50), (800, "a"): ("OK", 60)}
    c, _, recs, _ = fake_scan(table, check=check)
    assert checked == [(1200, "a"), (800, "a")], checked
    assert recs[2]["decision"] == "in window, second condition failed" and recs[2]["second_condition"]["status"] == "TO"
    assert (c["tau_s"], c["rho_w"]) == (800, 0.5) and c["second_condition"]["status"] == "OK", c
    c, reason, _, _ = fake_scan({(1600, "a"): ("OK", 50), (1600, "b"): ("OK", 400), (1200, "a"): ("OK", 400)},
                                check=lambda s, tw: (False, {}))
    assert c is None and "every rho_w" in reason, reason


@case("selection: scan order is tau_s descending, then rho_w 0.5 before 0.7")
def _():
    order = lib.selection_order({0.5: "a", 0.7: "b"})
    assert order[:3] == [(1600, 0.5, "a"), (1600, 0.7, "b"), (1200, 0.5, "a")]
    assert len(order) == 2 * len(lib.TAU_S_GRID)
    for name, d in lib.DATASETS.items():
        g = d["tau_s_grid"]
        assert list(g) == sorted(set(g), reverse=True), f"{name}: grid not strictly descending"
        assert lib.selection_order({0.5: "a", 0.7: "b"}, g)[0] == (g[0], 0.5, "a")
        assert set(d["members"]) == {d["edges"], d["features"], d["target"]}, name
        pr = d["protocol"]
        assert set(pr) == {"selection_limit_ms", "warmup", "measured"} and pr["warmup"] >= 1 and pr["measured"] >= 1, name
    assert lib.DATASETS["lastfm"]["protocol"] == {"selection_limit_ms": 600000, "warmup": 1, "measured": 5}
    assert {7500, 7000, 6500} <= set(lib.DATASETS["github"]["tau_s_grid"])
    assert lib.DATASETS["lastfm"]["tau_s_grid"] == lib.TAU_S_GRID, "the LastFM grid of the measured run"


# ---------------------------------------------------------------- pattern sets and features

@case("image sets: matrix entries are exactly the images; bad lines are rejected")
def _():
    with tempfile.TemporaryDirectory() as tmp:
        p = os.path.join(tmp, "img.tsv")
        with open(p, "w") as f:
            f.write("pattern\tvertex\tcode\tsize\timages\n0\t0\tc\t2\t1 3\n0\t1\tc\t1\t2\n1\t0\td\t2\t1 3\n")
        sets = lib.read_image_sets(p)
        m = lib.pattern_matrix(4, sets).toarray()
        assert m.tolist() == [[0, 0, 0], [1, 0, 1], [0, 1, 0], [1, 0, 1]], m.tolist()
        assert raises(lambda: lib.pattern_matrix(3, sets)), "vertex id 3 is out of range for n=3"
        with open(p, "w") as f:
            f.write("pattern\tvertex\tcode\tsize\timages\n0\t0\tc\t3\t1 3\n")
        assert raises(lambda: lib.read_image_sets(p)), "size 3 with 2 ids"
        with open(p, "w") as f:
            f.write("pattern\tvertex\tcode\tsize\timages\n0\t0\tc\t0\t\n")
        assert raises(lambda: lib.read_image_sets(p)), "empty image set"


@case("column dedupe: equal columns merged (first kept, order kept); one differing row keeps both")
def _():
    from scipy import sparse
    import numpy as np
    m = sparse.csr_matrix(np.array([[1, 0, 1, 0, 1], [0, 1, 0, 1, 0], [1, 1, 1, 0, 0]], dtype=float))
    d, before, after = lib.dedupe_columns(m)
    # columns: c0=(0,2) c1=(1,2) c2=(0,2) c3=(1) c4=(0): c2 repeats c0
    assert (before, after) == (5, 4), (before, after)
    assert d.toarray().tolist() == [[1, 0, 0, 1], [0, 1, 1, 0], [1, 1, 0, 0]], d.toarray().tolist()
    single = sparse.csr_matrix(np.array([[1], [0]], dtype=float))
    assert lib.dedupe_columns(single)[1:] == (1, 1)
    same = sparse.csr_matrix(np.ones((3, 4)))
    assert lib.dedupe_columns(same)[1:] == (4, 1), "all-equal columns collapse to one"


@case("pattern dump: both headers read, a short line rejected")
def _():
    with tempfile.TemporaryDirectory() as tmp:
        p = os.path.join(tmp, "d.tsv")
        with open(p, "w") as f:
            f.write("code\tvertices\tedges\tweight\nA\t2\t1\t40.5\nB\t3\t2\t41\n")
        assert lib.read_dump(p) == [("A", 2, 1, 40.5), ("B", 3, 2, 41.0)]
        with open(p, "w") as f:
            f.write("code\tvertices\tedges\tmni\tweight\nA\t2\t1\t9\t40.5\n")
        assert lib.read_dump(p) == [("A", 2, 1, 40.5)]
        with open(p, "w") as f:
            f.write("code\tvertices\tedges\tweight\nA\t2\t1\n")
        assert raises(lambda: lib.read_dump(p))
        with open(p, "w") as f:
            f.write("pattern\tvertex\n")
        assert raises(lambda: lib.read_dump(p))


@case("same-count set: MNI is the smallest image set; largest MNI first, ties by ascending code")
def _():
    rows = [(0, 0, [1, 2, 3]), (0, 1, [1, 2]), (1, 0, [4, 5, 6]), (1, 1, [1, 2, 3]),
            (2, 0, [7, 8, 9]), (3, 0, [1]), (4, 0, [1, 2, 3])]
    mni = lib.mni_of(rows)
    assert mni == {0: 2, 1: 3, 2: 3, 3: 1, 4: 3}, mni
    codes = ["e", "c", "b", "a", "d"]
    assert lib.top_by_mni(codes, mni, 3) == [2, 1, 4], "MNI 3 ties ordered by code b < c < d"
    assert lib.top_by_mni(codes, mni, 5) == [2, 1, 4, 0, 3]
    assert lib.top_by_mni(codes, mni, 0) == []
    assert raises(lambda: lib.top_by_mni(codes, mni, 6))


@case("baselines: B0 is degree and incident weight; B1 appends the artist features; bad ids refused")
def _():
    with tempfile.TemporaryDirectory() as tmp:
        p = os.path.join(tmp, "f.json")
        with open(p, "w") as f:
            f.write('{"0": [5, 9], "1": [9, 9], "2": []}')
        b = lib.baselines(3, [(0, 1, 2.0), (1, 2, 3.0)], p)
        assert b["B0"].toarray().tolist() == [[1, 2], [2, 5], [1, 3]], b["B0"].toarray().tolist()
        assert b["B1"].toarray().tolist() == [[1, 2, 1, 1], [2, 5, 0, 1], [1, 3, 0, 0]], b["B1"].toarray().tolist()
        with open(p, "w") as f:
            f.write('{"0": [5], "2": []}')
        assert raises(lambda: lib.baselines(3, [], p))


# ---------------------------------------------------------------- evaluation

@case("corrected t-test: value of the Nadeau-Bengio formula, df, zero variance")
def _():
    import math
    from scipy.stats import t as tdist
    d = [0.01, 0.02, 0.03, 0.04, 0.05, 0.02]
    m = sum(d) / 6
    s2 = sum((x - m) ** 2 for x in d) / 5
    t_hand = m / math.sqrt((1 / 6 + 10 / 90) * s2)
    mean, t, df, p = lib.corrected_t(d, 90, 10)
    assert abs(mean - m) < 1e-15 and abs(t - t_hand) < 1e-12 and df == 5, (t, t_hand, df)
    assert abs(p - 2 * tdist.sf(t_hand, 5)) < 1e-12
    naive = m / math.sqrt(s2 / 6)
    assert t < naive, "the correction must widen the variance"
    assert lib.corrected_t([0.0] * 4, 90, 10)[3] == 1.0
    assert lib.corrected_t([0.1] * 4, 90, 10)[3] == 0.0
    assert lib.corrected_t([-0.1, 0.1], 90, 10)[1] == 0.0


@case("configurations: two baselines alone, then each with the five pattern sets, in this order")
def _():
    names = lib.config_names()
    assert len(names) == 12 and len(set(names)) == 12, names
    assert names[:3] == ["B0", "B1", "B0 + FWS"] and names[-1] == "B1 + ALL (images in heavy edges)", names
    assert [g for _n, g in lib.PATTERN_SETS] == ["full", "full", "full", "full", "filtered"]
    assert lib.SEEDS == (1, 2, 3) and lib.FOLDS == 10 and lib.INNER_FOLDS == 3
    assert lib.C_GRID == (0.01, 0.1, 1.0, 10.0)


def toy_evaluation():
    import numpy as np
    from scipy import sparse
    rng = np.random.default_rng(0)
    y = np.repeat([0, 1, 2], 30)
    cont = np.abs(rng.normal(size=(90, 2))) * [10.0, 1000.0]   # degree and weight are non-negative
    noise = sparse.csr_matrix(np.column_stack([cont, rng.integers(0, 2, size=(90, 2))]))
    informative = sparse.csr_matrix(np.column_stack([cont, np.eye(3)[y]]))
    mats = {}
    for name in lib.config_names():
        if name.endswith("FWS"):
            mats[name] = informative
        elif name.endswith("(images in heavy edges)"):
            mats[name] = None
        else:
            mats[name] = noise
    return mats, y


@case("evaluation: informative features win the corrected test; an empty set is recorded; folds paired")
def _():
    mats, y = toy_evaluation()
    rows = lib.evaluate(mats, y, seeds=(1,), folds=3, log=lambda m: None)
    assert len(rows) == 12 * 3 and sum(r["empty"] for r in rows) == 2 * 3
    s = lib.summarise(rows)
    assert s["per_config"]["B0 + FWS"]["macro_f1_mean"] > 0.95, s["per_config"]["B0 + FWS"]
    assert s["per_config"]["B0"]["macro_f1_mean"] < 0.6, s["per_config"]["B0"]
    assert s["per_config"]["B1 + ALL (images in heavy edges)"] == {"folds": 3, "empty": True}
    assert sum(s["per_config"]["B0"]["C_chosen"].values()) == 3
    tests = [t for t in s["tests"] if t["reference"] == "B0 + FWS"]
    assert len(tests) == 2 * 4 + 1, len(tests)  # base and three non-empty sets on two metrics, one empty set
    t = next(t for t in tests if t.get("other") == "B0" and t.get("metric") == "macro_f1")
    assert t["pairs"] == 3 and t["df"] == 2 and t["mean_difference"] > 0.3 and t["reference_better"] == 3, t
    assert any(t.get("other") == "B0 + ALL (images in heavy edges)" and t.get("empty") for t in tests)
    unpaired = [r for r in rows if not (r["features"] == "B0" and r["fold"] == 0)]
    assert raises(lambda: lib.summarise(unpaired)), "missing fold must be refused"
    missing = [r for r in rows if r["features"] != "B1 + Frequent (all)"]
    assert raises(lambda: lib.summarise(missing)), "a missing configuration must be refused, not read as empty"


@case("evaluation: the continuous columns are standardised, so a signal in small units is used")
def _():
    import numpy as np
    from scipy import sparse
    from sklearn.metrics import f1_score
    rng = np.random.default_rng(1)
    y = np.repeat([0, 1], 60)
    tiny = y * 1e-3 + np.abs(rng.normal(scale=1e-5, size=120))  # separates the classes, in units of 1e-3
    x = sparse.csr_matrix(np.column_stack([tiny, np.abs(rng.normal(size=120)), rng.integers(0, 2, size=(120, 2))]))
    m = lib.model(1).fit(x, y)
    assert f1_score(y, m.predict(x), average="macro") > 0.95, "the unscaled column would be shrunk to zero"


@case("evaluation: the continuous columns are log(1 + x) then standardised; binary columns untouched")
def _():
    import numpy as np
    from scipy import sparse
    x = np.array([[0.0, 1.0, 1, 0], [3.0, 10.0, 0, 1], [99.0, 1000.0, 1, 1], [7.0, 0.0, 0, 0]])
    step = lib.model(1).estimator.steps[0][1].fit(sparse.csr_matrix(x))
    got = step.transform(sparse.csr_matrix(x))
    got = got.toarray() if hasattr(got, "toarray") else got
    lg = np.log1p(x[:, :2])
    want = (lg - lg.mean(axis=0)) / lg.std(axis=0)
    assert np.allclose(got[:, :2], want) and np.array_equal(got[:, 2:], x[:, 2:]), got
    assert raises(lambda: lib.log_dense(np.array([[-1.0, 2.0]])))


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
