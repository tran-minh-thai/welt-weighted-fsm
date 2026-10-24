"""Diagnostic (probe): is the B0 result limited by the linear model on raw, skewed columns?
Same B0 columns, stratified 10-fold, seed 1; three models: the design's model, the design's
model on log1p columns, and a histogram gradient-boosting classifier (balanced class weights).
Usage: python diagnose_b0_model.py <graph.lg> <labels.lg> <min_class>"""
import collections, json, sys, os
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "tools", "downstream"))
import lib
import numpy as np
from scipy import sparse
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.metrics import f1_score
from sklearn.model_selection import StratifiedKFold
g, labels, mc = sys.argv[1], sys.argv[2], int(sys.argv[3])
_, edges = lib.read_lg(g); y_all, _ = lib.read_lg(labels)
cnt = collections.Counter(y_all); keep = [v for v in range(len(y_all)) if cnt[y_all[v]] >= mc]
y = np.asarray([y_all[v] for v in keep]); x = lib.structural_columns(len(y_all), edges)[keep]
out = {"vertices": len(keep), "classes": len(set(y))}
for name, make, feat in (("design LR", lambda: lib.model(1), sparse.csr_matrix(x)),
                         ("design LR on log1p", lambda: lib.model(1), sparse.csr_matrix(np.log1p(x))),
                         ("gradient boosting", lambda: HistGradientBoostingClassifier(class_weight="balanced", random_state=1), x)):
    f = []
    for tr, te in StratifiedKFold(10, shuffle=True, random_state=1).split(x, y):
        f.append(f1_score(y[te], make().fit(feat[tr], y[tr]).predict(feat[te]), average="macro"))
    out[name] = {"macro_f1_mean": float(np.mean(f)), "macro_f1_sd": float(np.std(f, ddof=1)), "folds": len(f)}
print(json.dumps(out))
