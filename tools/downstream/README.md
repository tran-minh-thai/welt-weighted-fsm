# Downstream node classification with WeLT patterns (LastFM Asia)

Question: do features built from the weighted patterns that WeLT mines improve node
classification, compared with the node features alone, when no class information can reach the
mining step?

## One command

```bash
python -m pip install -r tools/downstream/requirements.txt   # exact versions, once per environment
python tools/downstream/b6.py                                 # probe run on any machine
```

On the measurement machine the step is run by the campaign driver, which uses the campaign's
voting choice, heap limit, time limit and warmup/measured protocol:

```bash
B6_PYTHON=/path/to/python-with-requirements JAVA_OPTS="-Xmx48g" bash revision.sh b6
```

`b6.py` builds the Java code, fetches the raw MUSAE LastFM Asia archive from SNAP when it is
missing (SHA-256 checked, archive and members), and writes everything into one output directory:
`results-probe/b6/<date>_<host>/` by default, `<campaign>/B6_downstream/` under `revision.sh`.
Options: `--out`, `--kind probe|measurement`, `--voting voting_choice.json`, `--limit-ms`,
`--warmup`, `--measured`, `--skip-build`. `python tools/downstream/selftest.py` checks the Python
side (every check also runs on an input built to fail it); `ImageSetMainTest` (in `./test.sh`)
checks the image-set export against a brute-force enumeration.

## Design

| item | choice |
|---|---|
| data | LastFM Asia (MUSAE): 7,624 users, 27,806 mutual-follower edges, artist features, `target` = country (18 classes) |
| mining graph | `datasets/lastfm_struct.lg`: the vertices, edges and weights of `datasets/lastfm.lg` (weight = Jaccard of the artist sets x 100), vertex label = degree class |
| degree classes | `q_k = sorted_degrees[floor(k n / 5)]`, k = 1..4; `label(v) = #{k : deg(v) > q_k}`. Ties are kept together: cut points 1, 3, 5, 10, class sizes 1,754 / 1,979 / 1,083 / 1,369 / 1,439 |
| thresholds | scan `tau_s` in 1600, 1200, 1000, 800, 600, 500, 400, 300, 250, 200, 150, 100, 75, 50, 30, 20, 10 (descending), and for each `rho_w` in 0.5, 0.7 (in that order), with `tau_w = w_min + rho_w (w_max - w_min)` (the mapping of the tau_w sweep, `SharedTableSweepMain --grid`). The first cell whose WeLT result set has 20 to 300 patterns is used (`b6lib.scan`). A cell above the window skips every lower `tau_s` at the same `rho_w` (a lower support only adds patterns). A selection run that does not complete within `--selection-limit-ms` (default 10 min) keeps the patterns accepted so far, which are true results (the limit only makes embedding searches fail, never succeed), so its count is a lower bound: above 300 it decides "above the window"; at or below 300 the scan stops without a choice. Consequence: another machine or limit yields the same choice or an explicit stop, never a different choice |
| mining | `BenchmarkMain`, algorithm WeLT, exact mode (budget 0), `DUMP_PATTERNS` (exact MNI and W per pattern), voting from `voting_choice.json` when given |
| pattern features | for each pattern P and pattern vertex u, a binary column: vertex v has 1 iff v is a valid image of u in some occurrence of P inside G restricted to the edges of weight >= tau_w. These are the image sets of the MNI definition on the filtered graph (`ImageSetMain`, `MniSupportCounter.exactImageSets`, no early stopping) |
| baseline features | one binary column per artist id of `features.json`, plus degree and total incident weight |
| model | `StandardScaler(with_mean=False)` then `LogisticRegression(class_weight="balanced", max_iter=5000)`, other parameters at the scikit-learn defaults (no tuning) |
| protocol | stratified 10-fold, shuffled with seeds 0, 1, 2 (30 folds); the three feature sets (baseline; baseline + patterns; patterns) see the same folds |
| reported | macro-F1 and accuracy, mean and SD over the 30 folds; paired two-sided Wilcoxon signed-rank test over the 30 folds, baseline + patterns minus baseline |

The scaler does not centre because the matrices are sparse; it divides every column by its
standard deviation, estimated on the training fold.

## Leakage controls

1. **No class label in the mining graph.** `datasets/lastfm.lg` uses `target` as vertex label and
   is not used for mining. `datasets/convert_musae_struct.py` builds `lastfm_struct.lg` from the
   edge list and the features only; it never opens `target.csv`.
2. **Checked, not assumed.** Before mining, `b6.py` (a) verifies the SHA-256 of the raw files, of
   `lastfm.lg` and of `lastfm_struct.lg`; (b) rebuilds `lastfm_struct.lg` from the raw files and
   requires it to equal the committed file byte for byte; (c) compares it with `lastfm.lg`: the
   `t` line, the vertex ids and all 27,806 `e` lines must be identical; (d) recomputes the degree
   classes from the edges and requires them to equal the vertex labels; (e) requires the labels
   to differ from `target`. The facts are written to `run_meta.json` (`leakage_check`).
3. **No class label in the threshold choice.** The rule uses only the number of mined patterns;
   the grid, the order and the window are fixed in `b6lib.py` before any run.
4. **No class label in the features.** Pattern features depend only on the graph, the patterns
   and tau_w. The setting is transductive: patterns are mined once on the whole graph (structure
   and weights of every vertex, never its class), as in unsupervised node-feature extraction.
5. **Fold-local fitting.** The scaler and the classifier are fitted on the training fold only.

## Outputs

| file | content |
|---|---|
| `selection.json` | the rule, `tau_w` of each `rho_w`, every scanned cell (status, number of patterns) and the chosen cell |
| `selection_patterns/` | the pattern dumps of the scanned cells |
| `patterns/` | the pattern dump of the chosen cell (code, vertices, edges, exact MNI, exact W) |
| `mining.csv` | the `BenchmarkMain` counters of the chosen cell (no time or memory column) |
| `image_sets.tsv` | one line per (pattern, pattern vertex): its image set on the filtered graph |
| `folds.csv` | macro-F1, accuracy, iterations and convergence per (seed, fold, feature set) |
| `summary.json` | means, SDs, Wilcoxon tests, feature dimensions, class sizes |
| `timings_<kind>.json` | mining time (the `BenchmarkMain` time columns of the chosen cell), feature-building time (image-set export and matrix assembly), classification wall time |
| `run_meta.json` | command, commit, tree state, host, OS, Python and package versions, JDK, `JAVA_OPTS`, SHA-256 of the tool files and of the data, the design constants, the leakage facts |
| `console.txt` | the console output |

`--kind probe` (the default) marks the timings as a feasibility probe: they come from whatever
machine ran the script. Only a run on the measurement machine (`revision.sh b6`, which passes
`--kind measurement` and requires a clean working tree) gives timings that can be reported. The
classification metrics do not depend on the machine beyond floating-point differences of the
numerical libraries; the pattern set and the features are deterministic.
