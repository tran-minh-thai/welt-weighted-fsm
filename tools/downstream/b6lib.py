"""Functions of the downstream node-classification experiment (see README.md in this folder).

Kept free of side effects so that selftest.py can check each of them, including on inputs
built to make them fail.
"""
import csv
import hashlib
import json

# ---------------------------------------------------------------- fixed design (set before any run)

# Support thresholds scanned in descending order; for each, rho_w in RHO_W order. The first pair
# whose WeLT result set has PATTERN_WINDOW[0] <= |R| <= PATTERN_WINDOW[1] patterns is used (see
# scan). The grid and the rule use no class label (only the size of the result set).
TAU_S_GRID = (1600, 1200, 1000, 800, 600, 500, 400, 300, 250, 200, 150, 100, 75, 50, 30, 20, 10)
RHO_W = (0.5, 0.7)
PATTERN_WINDOW = (20, 300)

SEEDS = (0, 1, 2)          # one StratifiedKFold shuffle per seed
FOLDS = 10
MAX_ITER = 5000            # lbfgs iterations of the logistic regression
FEATURE_SETS = ("baseline", "baseline+patterns", "patterns")

# Raw MUSAE LastFM Asia archive (SNAP) and its members.
LASTFM_ZIP_URL = "https://snap.stanford.edu/data/lastfm_asia.zip"
LASTFM_ZIP_SHA = "51acb78a923bb223ed6e61be88f91122fb29adca3f07beff7289cafd98601d47"
LASTFM_MEMBERS = {
    "lasftm_asia/lastfm_asia_edges.csv": "548eacff61bff564ab5007831fa4a7a30e1a3956075ffd24e43310ed33555842",
    "lasftm_asia/lastfm_asia_features.json": "93fe84c96ffa9f0081ab37085979641062393895925de6c344df0f829c8ed2e7",
    "lasftm_asia/lastfm_asia_target.csv": "91f19215f6eca8321feec038bd32900cf13fd4f7e041a9976cf202807c72ac16",
}
LASTFM_LG_SHA = "be77963234648e22e998b703b2418f9d5dd030926713665fd049e7053c8ed3b7"
LASTFM_STRUCT_LG_SHA = "f80c68853ba6bea9f2f133b853ec114c197025b03a4e478741f229fbaec5d44c"


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


# ---------------------------------------------------------------- graph and leakage checks

def read_lg(path):
    """(labels, edges) of an .lg file: labels[v] for v = 0..n-1, edges as (a, b, weight)."""
    labels, edges = {}, []
    with open(path) as f:
        for line in f:
            p = line.split()
            if not p:
                continue
            if p[0] == "v":
                labels[int(p[1])] = int(p[2])
            elif p[0] == "e":
                edges.append((int(p[1]), int(p[2]), float(p[3])))
    n = len(labels)
    if sorted(labels) != list(range(n)):
        raise ValueError(f"{path}: vertex ids are not 0..{n - 1}")
    return [labels[v] for v in range(n)], edges


def degree_classes(n, edges):
    """The labels of datasets/convert_musae_struct.py: |{k : deg(v) > q_k}|, q_k the k-th degree quintile."""
    deg = [0] * n
    for a, b, *_ in edges:
        deg[a] += 1
        deg[b] += 1
    s = sorted(deg)
    cuts = [s[(k * n) // 5] for k in range(1, 5)]
    return [sum(1 for q in cuts if d > q) for d in deg]


def leakage_report(struct_labels, struct_edges, ref_labels, ref_edges, target):
    """Checks that the mining graph equals the reference graph up to vertex labels, that its
    labels are the degree classes, and that they are not the class target. Returns (ok, facts)."""
    n = len(struct_labels)
    facts = {
        "vertices": n,
        "edges_compared": len(ref_edges),
        "edges_equal": struct_edges == ref_edges,
        "vertex_count_equal": n == len(ref_labels) == len(target),
        "labels_are_degree_classes": struct_labels == degree_classes(n, struct_edges),
        "distinct_labels": len(set(struct_labels)),
        "vertices_whose_label_equals_target": sum(1 for a, t in zip(struct_labels, target) if a == t),
    }
    ok = (facts["edges_equal"] and facts["vertex_count_equal"] and facts["labels_are_degree_classes"]
          and struct_labels != list(target))
    return ok, facts


def read_target(path):
    with open(path, newline="") as f:
        r = csv.reader(f)
        header = next(r)
        if header[0] != "id":
            raise ValueError(f"{path}: unexpected header {header}")
        rows = {int(row[0]): int(row[-1]) for row in r}
    n = len(rows)
    if sorted(rows) != list(range(n)):
        raise ValueError(f"{path}: ids are not 0..{n - 1}")
    return [rows[v] for v in range(n)]


# ---------------------------------------------------------------- threshold selection

def selection_order(tau_w_of_rho):
    """The (tau_s, rho_w, tau_w) pairs in scan order."""
    return [(s, r, tau_w_of_rho[r]) for s in TAU_S_GRID for r in RHO_W]


def scan(order, run):
    """Scans the (tau_s, rho_w, tau_w) cells in order and returns (index of the chosen record or
    None, reason, records). run(tau_s, tau_w) -> (status, R).

    A cell decides by its pattern count R: inside the window it is chosen and the scan stops;
    below it the scan goes on. Above it (R > hi) every later cell with the same rho_w is skipped
    without running: a lower tau_s only adds patterns. A run that did not complete (TO, OOM) keeps
    the patterns accepted so far, which are true results (the time limit only makes searches
    fail), so its R is a lower bound: R > hi decides "above"; R <= hi decides nothing and stops
    the scan without a choice. The scan stops without a choice when every rho_w is above."""
    lo, hi = PATTERN_WINDOW
    above = set()
    records = []
    for s, r, tw in order:
        if r in above:
            records.append({"tau_s": s, "rho_w": r, "tau_w": tw, "status": "SKIPPED", "R": None,
                            "decision": "above (a higher tau_s at this rho_w is above the window)"})
            continue
        status, n = run(s, tw)
        rec = {"tau_s": s, "rho_w": r, "tau_w": tw, "status": status, "R": n}
        records.append(rec)
        if n > hi:
            rec["decision"] = "above" if status == "OK" else "above (lower bound of a run that did not complete)"
            above.add(r)
            if above >= set(RHO_W):
                return None, f"every rho_w is above the window once tau_s <= {s}; no cell has {lo} <= |R| <= {hi}", records
        elif status != "OK":
            rec["decision"] = "undecided"
            return None, (f"cell tau_s={s} rho_w={r} ended with status {status} at |R|={n} <= {hi}: "
                          f"its pattern count is unknown; the scan stops without a choice"), records
        elif n >= lo:
            rec["decision"] = "chosen"
            return len(records) - 1, f"first cell with {lo} <= |R| <= {hi}: tau_s={s} rho_w={r} |R|={n}", records
        else:
            rec["decision"] = "below"
    return None, f"no cell of the grid has {lo} <= |R| <= {hi}", records


# ---------------------------------------------------------------- features

def read_image_sets(path):
    """[(pattern, vertex, images)] from the ImageSetMain output, checked line by line."""
    out = []
    with open(path) as f:
        header = f.readline().rstrip("\n")
        if header != "pattern\tvertex\tcode\tsize\timages":
            raise ValueError(f"{path}: unexpected header {header!r}")
        for line in f:
            p, u, _code, size, images = line.rstrip("\n").split("\t")
            img = [int(x) for x in images.split()]
            if len(img) != int(size) or len(img) == 0:
                raise ValueError(f"{path}: pattern {p} vertex {u}: size {size}, {len(img)} ids")
            out.append((int(p), int(u), img))
    return out


def pattern_matrix(n, image_sets):
    """Binary vertex x (pattern, pattern vertex) matrix: 1 iff the vertex is a valid image."""
    import numpy as np
    from scipy import sparse
    rows, cols = [], []
    for j, (_p, _u, img) in enumerate(image_sets):
        if max(img) >= n or min(img) < 0:
            raise ValueError(f"column {j}: vertex id out of range")
        rows.extend(img)
        cols.extend([j] * len(img))
    data = np.ones(len(rows), dtype=np.float64)
    return sparse.csr_matrix((data, (rows, cols)), shape=(n, len(image_sets)))


def baseline_matrix(features_json, n, edges):
    """Binary features of features.json (one column per feature id present), then degree and
    total incident weight, both from the full graph."""
    import numpy as np
    from scipy import sparse
    with open(features_json) as f:
        feats = {int(k): v for k, v in json.load(f).items()}
    if sorted(feats) != list(range(n)):
        raise ValueError(f"{features_json}: ids are not 0..{n - 1}")
    ids = sorted({x for v in feats.values() for x in v})
    col = {x: j for j, x in enumerate(ids)}
    rows, cols = [], []
    for v in range(n):
        for x in set(feats[v]):
            rows.append(v)
            cols.append(col[x])
    binary = sparse.csr_matrix((np.ones(len(rows)), (rows, cols)), shape=(n, len(ids)))
    deg = np.zeros(n)
    wsum = np.zeros(n)
    for a, b, w in edges:
        deg[a] += 1
        deg[b] += 1
        wsum[a] += w
        wsum[b] += w
    return sparse.hstack([binary, sparse.csr_matrix(np.column_stack([deg, wsum]))]).tocsr(), len(ids)


def distinct_columns(m):
    """Number of distinct columns of a sparse binary matrix (automorphic pattern vertices repeat)."""
    m = m.tocsc()
    return len({tuple(m.indices[m.indptr[j]:m.indptr[j + 1]]) for j in range(m.shape[1])})


# ---------------------------------------------------------------- evaluation

def model():
    from sklearn.linear_model import LogisticRegression
    from sklearn.pipeline import make_pipeline
    from sklearn.preprocessing import StandardScaler
    # sparse inputs: scaling to unit variance without centring (centring would densify)
    return make_pipeline(StandardScaler(with_mean=False),
                         LogisticRegression(class_weight="balanced", max_iter=MAX_ITER))


def evaluate(matrices, y, seeds=SEEDS, folds=FOLDS, log=print):
    """One row per (seed, fold, feature set) with macro-F1 and accuracy on the held-out fold.
    Every feature set sees the same folds; the scaler is fitted on the training fold only."""
    import warnings
    import numpy as np
    from sklearn.exceptions import ConvergenceWarning
    from sklearn.metrics import accuracy_score, f1_score
    from sklearn.model_selection import StratifiedKFold
    y = np.asarray(y)
    rows = []
    for seed in seeds:
        skf = StratifiedKFold(n_splits=folds, shuffle=True, random_state=seed)
        for fold, (tr, te) in enumerate(skf.split(np.zeros(len(y)), y)):
            for name, x in matrices.items():
                with warnings.catch_warnings(record=True) as caught:
                    warnings.simplefilter("always", ConvergenceWarning)
                    m = model().fit(x[tr], y[tr])
                pred = m.predict(x[te])
                rows.append({
                    "seed": seed, "fold": fold, "features": name,
                    "macro_f1": f1_score(y[te], pred, average="macro"),
                    "accuracy": accuracy_score(y[te], pred),
                    "iterations": int(np.max(m[-1].n_iter_)),
                    "converged": int(not any(issubclass(w.category, ConvergenceWarning) for w in caught)),
                })
            log(f"  seed {seed} fold {fold}: " + "  ".join(
                f"{r['features']} F1={r['macro_f1']:.4f}" for r in rows[-len(matrices):]))
    return rows


def summarise(rows, a="baseline", b="baseline+patterns"):
    """Mean and SD per feature set and metric, and the paired Wilcoxon test b vs a over the folds."""
    import numpy as np
    from scipy.stats import wilcoxon
    out = {"per_set": {}, "wilcoxon": {}}
    names = sorted({r["features"] for r in rows}, key=lambda s: FEATURE_SETS.index(s) if s in FEATURE_SETS else 99)
    for name in names:
        rs = [r for r in rows if r["features"] == name]
        out["per_set"][name] = {
            "folds": len(rs),
            "not_converged": sum(1 - r["converged"] for r in rs),
            **{f"{k}_{s}": float(f(np.array([r[k] for r in rs]))) for k in ("macro_f1", "accuracy")
               for s, f in (("mean", np.mean), ("sd", lambda v: np.std(v, ddof=1)))},
        }
    key = lambda r: (r["seed"], r["fold"])
    ra = {key(r): r for r in rows if r["features"] == a}
    rb = {key(r): r for r in rows if r["features"] == b}
    if set(ra) != set(rb):
        raise ValueError("the two feature sets were not evaluated on the same folds")
    pairs = sorted(ra)
    for k in ("macro_f1", "accuracy"):
        d = np.array([rb[p][k] - ra[p][k] for p in pairs])
        res = wilcoxon(d, zero_method="wilcox", alternative="two-sided") if np.any(d != 0) else None
        out["wilcoxon"][k] = {
            "pairs": len(pairs), "zero_differences": int(np.sum(d == 0)),
            "b_better": int(np.sum(d > 0)), "a_better": int(np.sum(d < 0)),
            "mean_difference": float(np.mean(d)), "median_difference": float(np.median(d)),
            "statistic": None if res is None else float(res.statistic),
            "p_value": None if res is None else float(res.pvalue),
            "compared": f"{b} minus {a}",
        }
    return out
