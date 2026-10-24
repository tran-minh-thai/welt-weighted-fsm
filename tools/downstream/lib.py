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

SEEDS = (1, 2, 3)          # one StratifiedKFold shuffle per seed
FOLDS = 10
INNER_FOLDS = 3            # stratified folds inside each training fold, to choose C
C_GRID = (0.01, 0.1, 1.0, 10.0)
MAX_ITER = 5000            # lbfgs iterations of the logistic regression

# Pattern sets (all at the chosen (tau_s, tau_w)) and the graph their image sets are taken from.
# "full" is G (the MNI of a frequent weighted subgraph is defined over G); "filtered" is G
# restricted to the edges of weight >= tau_w (the occurrences counted by the ALL score).
PATTERN_SETS = (
    ("FWS", "full"),
    ("Frequent (same count)", "full"),
    ("Frequent (all)", "full"),
    ("ALL (images in G)", "full"),
    ("ALL (images in heavy edges)", "filtered"),
)
BASELINES = ("B0", "B1")   # B0: degree and total incident weight; B1: B0 and the artist features
REFERENCE = "FWS"          # the paired tests compare base + FWS with every other set at the same base

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


def scan(order, run, check=None):
    """Scans the (tau_s, rho_w, tau_w) cells in order and returns (index of the chosen record or
    None, reason, records). run(tau_s, tau_w) -> (status, R). check(tau_s, tau_w) -> (ok, note)
    is the second condition, tested only on a cell whose R is inside the window; a cell that fails
    it is recorded ("in window, second condition failed") and the scan goes on.

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
            if check is not None:
                ok, note = check(s, tw)
                rec["second_condition"] = note
                if not ok:
                    rec["decision"] = "in window, second condition failed"
                    continue
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


def structural_columns(n, edges):
    """Degree and total incident weight of every vertex (the B0 baseline), as a dense n x 2 array."""
    import numpy as np
    deg = np.zeros(n)
    wsum = np.zeros(n)
    for a, b, w in edges:
        deg[a] += 1
        deg[b] += 1
        wsum[a] += w
        wsum[b] += w
    return np.column_stack([deg, wsum])


def artist_matrix(features_json, n):
    """Binary features of features.json, one column per feature id present."""
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
    return sparse.csr_matrix((np.ones(len(rows)), (rows, cols)), shape=(n, len(ids)))


def baselines(n, edges, features_json):
    """{"B0": structural columns, "B1": structural columns then artist features}; the two
    continuous columns come first in both (see model)."""
    from scipy import sparse
    s = sparse.csr_matrix(structural_columns(n, edges))
    return {"B0": s, "B1": sparse.hstack([s, artist_matrix(features_json, n)]).tocsr()}


def read_dump(path):
    """[(code, vertices, edges, weight)] of a PatternDump file (4 columns, or 5 with mni)."""
    with open(path) as f:
        header = f.readline().rstrip("\n").split("\t")
        if header not in (["code", "vertices", "edges", "weight"], ["code", "vertices", "edges", "mni", "weight"]):
            raise ValueError(f"{path}: not a pattern dump ({header})")
        out = []
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) != len(header):
                raise ValueError(f"{path}: expected {len(header)} fields: {line!r}")
            out.append((p[0], int(p[1]), int(p[2]), float(p[-1])))
    return out


def mni_of(image_sets):
    """{pattern index: MNI}, the smallest image-set size over the pattern's vertices."""
    m = {}
    for p, _u, img in image_sets:
        m[p] = min(m.get(p, len(img)), len(img))
    return m


def top_by_mni(codes, mni, k):
    """Indices of the k patterns of largest MNI, ties broken by ascending canonical code."""
    if k > len(codes):
        raise ValueError(f"asked for {k} patterns out of {len(codes)}")
    return sorted(range(len(codes)), key=lambda i: (-mni[i], codes[i]))[:k]


def dedupe_columns(m):
    """The matrix without repeated columns (first occurrence kept), and the column counts before
    and after."""
    m = m.tocsc()
    seen, keep = set(), []
    for j in range(m.shape[1]):
        key = tuple(m.indices[m.indptr[j]:m.indptr[j + 1]])
        if key not in seen:
            seen.add(key)
            keep.append(j)
    return m[:, keep].tocsr(), m.shape[1], len(keep)


# ---------------------------------------------------------------- evaluation

def to_dense(x):
    """The two continuous columns as a dense array (StandardScaler cannot centre a sparse matrix)."""
    return x.toarray() if hasattr(x, "toarray") else x


def model(seed, n_jobs=None):
    """Logistic regression with class-balanced weights; the two continuous columns (0 and 1) are
    standardised on the training data, the binary columns pass through; C is chosen in C_GRID by
    stratified INNER_FOLDS-fold cross-validation inside the training data (macro-F1)."""
    from sklearn.compose import ColumnTransformer
    from sklearn.linear_model import LogisticRegression
    from sklearn.model_selection import GridSearchCV, StratifiedKFold
    from sklearn.pipeline import Pipeline
    from sklearn.preprocessing import FunctionTransformer, StandardScaler
    continuous = Pipeline([("dense", FunctionTransformer(to_dense, accept_sparse=True)), ("std", StandardScaler())])
    pipe = Pipeline([
        ("scale", ColumnTransformer([("continuous", continuous, [0, 1])], remainder="passthrough",
                                    sparse_threshold=1.0)),
        ("lr", LogisticRegression(class_weight="balanced", max_iter=MAX_ITER)),
    ])
    inner = StratifiedKFold(n_splits=INNER_FOLDS, shuffle=True, random_state=seed)
    return GridSearchCV(pipe, {"lr__C": list(C_GRID)}, scoring="f1_macro", cv=inner, refit=True, n_jobs=n_jobs)


def config_names():
    """The evaluated configurations, in report order: every baseline alone, then with each pattern set."""
    return [b for b in BASELINES] + [f"{b} + {name}" for b in BASELINES for name, _g in PATTERN_SETS]


def evaluate(matrices, y, seeds=SEEDS, folds=FOLDS, log=print, n_jobs=None):
    """One row per (seed, fold, configuration) with macro-F1, accuracy and the chosen C on the
    held-out fold. matrices maps a configuration name to a matrix, or to None for an empty pattern
    set (its rows are written with empty=1 and no metric). Every configuration sees the same folds."""
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
                if x is None:
                    rows.append({"seed": seed, "fold": fold, "features": name, "empty": 1, "macro_f1": "",
                                 "accuracy": "", "C": "", "n_train": len(tr), "n_test": len(te), "converged": ""})
                    continue
                with warnings.catch_warnings(record=True) as caught:
                    warnings.simplefilter("always", ConvergenceWarning)
                    m = model(seed, n_jobs).fit(x[tr], y[tr])
                pred = m.predict(x[te])
                rows.append({
                    "seed": seed, "fold": fold, "features": name, "empty": 0,
                    "macro_f1": f1_score(y[te], pred, average="macro"),
                    "accuracy": accuracy_score(y[te], pred),
                    "C": m.best_params_["lr__C"], "n_train": len(tr), "n_test": len(te),
                    "converged": int(not any(issubclass(w.category, ConvergenceWarning) for w in caught)),
                })
            log(f"  seed {seed} fold {fold}: " + "  ".join(
                f"{r['features']}={r['macro_f1']:.4f}" if r["empty"] == 0 else f"{r['features']}=empty"
                for r in rows[-len(matrices):]))
    return rows


def corrected_t(d, n_train, n_test):
    """Nadeau-Bengio corrected resampled t-test for repeated k-fold cross-validation.

    d: paired differences over the k * r folds. The variance of their mean is
    (1 / (k r) + n_test / n_train) * s^2 instead of s^2 / (k r), because the training sets overlap.
    Returns (mean, t, degrees of freedom, two-sided p)."""
    import numpy as np
    from scipy.stats import t as tdist
    d = np.asarray(d, dtype=float)
    kr = len(d)
    mean = float(np.mean(d))
    var = float(np.var(d, ddof=1))
    if var == 0.0:
        return mean, (0.0 if mean == 0 else float("inf") * np.sign(mean)), kr - 1, (1.0 if mean == 0 else 0.0)
    t = mean / np.sqrt((1.0 / kr + n_test / n_train) * var)
    return mean, float(t), kr - 1, float(2 * tdist.sf(abs(t), kr - 1))


def summarise(rows):
    """Mean and SD per configuration and metric, and for each baseline the corrected paired test of
    "<base> + FWS" against every other configuration with the same base (the base alone included)."""
    import numpy as np
    out = {"per_config": {}, "tests": []}
    names = list(dict.fromkeys(r["features"] for r in rows))
    for name in names:
        rs = [r for r in rows if r["features"] == name]
        if all(r["empty"] == 1 for r in rs):
            out["per_config"][name] = {"folds": len(rs), "empty": True}
            continue
        out["per_config"][name] = {
            "folds": len(rs), "empty": False,
            "not_converged": sum(1 - r["converged"] for r in rs),
            "C_chosen": {str(c): sum(1 for r in rs if r["C"] == c) for c in C_GRID},
            **{f"{k}_{s}": float(f(np.array([r[k] for r in rs]))) for k in ("macro_f1", "accuracy")
               for s, f in (("mean", np.mean), ("sd", lambda v: np.std(v, ddof=1)))},
        }
    key = lambda r: (r["seed"], r["fold"])
    for base in BASELINES:
        ref = f"{base} + {REFERENCE}"
        ra = {key(r): r for r in rows if r["features"] == ref}
        for other in [base] + [f"{base} + {n}" for n, _g in PATTERN_SETS if n != REFERENCE]:
            rb = {key(r): r for r in rows if r["features"] == other}
            if not rb or not ra:
                raise ValueError(f"configuration {ref if not ra else other} has no evaluated fold")
            if all(r["empty"] == 1 for r in rb.values()):
                out["tests"].append({"reference": ref, "other": other, "empty": True})
                continue
            if set(ra) != set(rb):
                raise ValueError(f"{ref} and {other} were not evaluated on the same folds")
            pairs = sorted(ra)
            for k in ("macro_f1", "accuracy"):
                d = [ra[p][k] - rb[p][k] for p in pairs]
                n_train, n_test = ra[pairs[0]]["n_train"], ra[pairs[0]]["n_test"]
                mean, t, df, pv = corrected_t(d, n_train, n_test)
                out["tests"].append({"reference": ref, "other": other, "metric": k, "pairs": len(pairs),
                                     "mean_difference": mean, "t": t, "df": df, "p_value": pv,
                                     "reference_better": int(sum(x > 0 for x in d)),
                                     "other_better": int(sum(x < 0 for x in d))})
    return out
