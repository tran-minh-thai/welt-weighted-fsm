# Downstream node classification with mined patterns (LastFM Asia)

Questions: (1) do features built from the frequent weighted subgraphs (FWS) that WeLT mines improve
node classification over node features alone, when no class information can reach the mining
step? (2) with the same number of patterns, are FWS better features than unweighted frequent
patterns (GraMi) and than the patterns of the ALL score?

## One command

```bash
python -m pip install -r tools/downstream/requirements.txt   # exact versions, once per environment
python tools/downstream/run.py                                # probe run on any machine
```

On the measurement machine the step is run by the campaign driver, which uses the campaign's
voting choice, heap limit, time limit and warmup/measured protocol, and refuses any departure
from the design below:

```bash
DOWNSTREAM_PYTHON=/path/to/python-with-requirements JAVA_OPTS="-Xmx48g" bash revision.sh downstream
```

`run.py` builds the Java code, fetches the raw MUSAE LastFM Asia archive from SNAP when it is
missing (SHA-256 checked, archive and members), and writes everything into one output directory:
`results-probe/downstream/<date>_<host>/` by default, `<campaign>/B8_downstream/` under
`revision.sh`. A probe may shorten the protocol (`--seeds`, `--folds`, `--warmup`, `--measured`,
the time limits); every departure is written to `run_meta.json` (`departures_from_design`).
`python tools/downstream/selftest.py` checks the Python side (every check also runs on an input
built to fail it); `ImageSetMainTest` and `MaxEdgesTest` (in `./test.sh`) check the image-set
export against a brute-force enumeration and the size bound of the miner.

## Design

| item | choice |
|---|---|
| data | LastFM Asia (MUSAE): 7,624 users, 27,806 mutual-follower edges, artist features, `target` = country (18 classes) |
| mining graph G | `datasets/lastfm_struct.lg`: the vertices, edges and weights of `datasets/lastfm.lg` (weight = Jaccard of the artist sets x 100), vertex label = degree class |
| degree classes | `q_k = sorted_degrees[floor(k n / 5)]`, k = 1..4; `label(v) = #{k : deg(v) > q_k}`. Ties are kept together: cut points 1, 3, 5, 10, class sizes 1,754 / 1,979 / 1,083 / 1,369 / 1,439 |
| threshold grid | `tau_s` in 1600, 1200, 1000, 800, 600, 500, 400, 300, 250, 200, 150, 100, 75, 50, 30, 20, 10 (descending), and for each `rho_w` in 0.5, 0.7 (in that order), with `tau_w = w_min + rho_w (w_max - w_min)` (the mapping of the tau_w sweep, `SharedTableSweepMain --grid`) |
| selection rule | the first cell where (a) WeLT returns 20 to 300 FWS and (b) GraMi completes at the same `tau_s` within `--check-limit-ms` (60 min), mining the frequent patterns with at most as many edges as the largest FWS of the cell (`WELT_MAX_EDGES`). `DOWNSTREAM_FREQUENT_BOUND=none` drops the size bound (fallback). A cell above the window skips every lower `tau_s` at the same `rho_w`; a WeLT selection run that does not complete within `--selection-limit-ms` (10 min) keeps a lower bound of its count (the limit only makes embedding searches fail), so above 300 it decides "above", at or below 300 the scan stops without a choice (`lib.scan`). The choice is written to `downstream_choice.json` before any feature is built |
| pattern sets | FWS: the WeLT result set. Frequent (all): every pattern of the size-bounded GraMi run. Frequent (same count): the \|FWS\| frequent patterns of largest MNI, ties by ascending canonical code. ALL: GraMi on the graph of the edges of weight >= `tau_w` (`tools/heavy_subgraph.py`), the exact ALL set. The run checks ALL within FWS within Frequent (all) |
| pattern features | for each pattern P and pattern vertex u, a binary column: v has 1 iff v is a valid image of u in an occurrence of P (the image sets of the MNI definition, `ImageSetMain`, `MniSupportCounter.exactImageSets`, no early stopping). Images are taken in G for every set; ALL is evaluated twice, with images in G and in the graph of the heavy edges. Equal columns are merged (counts before and after are recorded) |
| baselines | B0: degree and total incident weight. B1: B0 and one binary column per artist id of `features.json` |
| configurations | B0, B1, and each base with each of the five pattern sets (twelve; `lib.config_names`). An empty pattern set is recorded as empty, not dropped |
| model | the two continuous columns standardised on the training data, binary columns unchanged; `LogisticRegression(class_weight="balanced", max_iter=5000)`; C in 0.01, 0.1, 1, 10 chosen by stratified 3-fold cross-validation inside the training data (macro-F1) |
| protocol | stratified 10-fold, shuffled with seeds 1, 2, 3 (30 folds); every configuration sees the same folds |
| reported | macro-F1 and accuracy, mean and SD over the 30 folds; for each base, base + FWS against every other configuration with that base: mean paired difference and the corrected resampled t-test of Nadeau and Bengio (variance factor 1/(k r) + n_test/n_train, k r - 1 degrees of freedom) |

## Leakage controls

1. **No class label in the mining graph.** `datasets/lastfm.lg` uses `target` as vertex label and
   is not used for mining. `datasets/convert_musae_struct.py` builds `lastfm_struct.lg` from the
   edge list and the features only; it never opens `target.csv`.
2. **Checked, not assumed.** Before mining, `run.py` (a) verifies the SHA-256 of the raw files, of
   `lastfm.lg` and of `lastfm_struct.lg`; (b) rebuilds `lastfm_struct.lg` from the raw files and
   requires it to equal the committed file byte for byte; (c) compares it with `lastfm.lg`: the
   `t` line, the vertex ids and all 27,806 `e` lines must be identical; (d) recomputes the degree
   classes from the edges and requires them to equal the vertex labels; (e) requires the labels
   to differ from `target`. The facts are written to `run_meta.json` (`leakage_check`).
3. **No class label in the threshold choice.** The rule uses only pattern counts and completion;
   the grid, the order and the window are fixed in `lib.py` before any run.
4. **No class label in the features.** Pattern features depend only on the graph, the patterns
   and `tau_w`. The setting is transductive: patterns are mined once on the whole graph (structure
   and weights of every vertex, never its class), as in unsupervised node-feature extraction.
5. **Fold-local fitting.** The scaler, the choice of C and the classifier use the training fold only.

## Outputs

| file | content |
|---|---|
| `downstream_choice.json` | the rule, `tau_w` of each `rho_w`, every scanned cell (status, counts, second condition) and the chosen cell |
| `selection_patterns/` | the pattern dumps of the selection runs |
| `mined/`, `patterns/` | the pattern dumps at the chosen cell (code, vertices, edges, exact W), one per set |
| `mining.csv` | the `BenchmarkMain` counters of the three mining runs (no time or memory column) |
| `heavy_edges.lg` | the graph of the edges of weight >= `tau_w` |
| `image_sets/` | one line per (pattern, pattern vertex) and set: its image set; `Frequent_same_count.selected.tsv` lists the chosen patterns with their MNI |
| `folds.csv` | macro-F1, accuracy, chosen C and convergence per (seed, fold, configuration) |
| `summary_configs.csv`, `summary_tests.csv`, `summary.json` | means, SDs, chosen C, paired tests, feature dimensions, class sizes, nesting counts |
| `timings_<kind>.json` | mining time per set, image-set export time, matrix assembly, classification wall time |
| `run_meta.json` | command, commit, tree state, host, OS, Python and package versions, JDK, `JAVA_OPTS`, SHA-256 of the tool files and of the data, the design, departures from it, the leakage facts |
| `console.txt` | the console output |

`--kind probe` (the default) marks the timings as a feasibility probe. Only a run on the
measurement machine (`revision.sh downstream`, which passes `--kind measurement` and requires a
clean working tree) gives timings that can be reported. The classification metrics do not depend
on the machine beyond floating-point differences of the numerical libraries; the pattern sets and
the features are deterministic.
