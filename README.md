# WeLT: weighted frequent subgraph mining with a lookup-table double filter

[![tests](https://github.com/tran-minh-thai/welt-weighted-fsm/actions/workflows/test.yml/badge.svg)](https://github.com/tran-minh-thai/welt-weighted-fsm/actions/workflows/test.yml)
[![Java](https://img.shields.io/badge/Java-17%2B-blue)](https://adoptium.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

Code, data scripts and experiment runners for:

> T.-D. Vo, B. Huynh, T. Tran. *WeLT: Weighted Frequent Subgraph Mining in a Single Large Graph via a
> Lookup-Table Double Filter.* Manuscript submitted for publication, 2026.

WeLT mines the subgraphs of a single labelled graph whose MNI support is at least `minSup` and whose
bottleneck weight (the smallest edge weight of the best embedding) is at least `τ_w`. Before any
support count, a table of the frequent 1- and 2-edge patterns rejects a candidate when one of its 2-edge
subpatterns is not frequent (P1) or when the smallest stored maximum weight is below `τ_w` (P2). The weight
check searches embeddings with a branch-and-bound on the running bottleneck, and a vote over domain size,
degree and weight orders the matching.

All algorithms share one mining engine (`src/main/java/welt/core/`) and one MNI counter, so they differ only in their
strategy.

| Algorithm | Problem | Source |
|---|---|---|
| WeLT | edge-bottleneck weight | this work |
| GraMiFilter | same as WeLT, same result set | GraMi search, then an exact weight check of each frequent pattern |
| GraMi | frequent subgraphs, no weights | independent reimplementation of the CSP-based MNI evaluation of Elseidy et al. (PVLDB 2014); no GraMi code is included; reproduces the original pattern sets (`GraMiOracleTest`) |
| ALL-exact | ReSuM's ALL score (Preti et al., EDBT 2018) | GraMi on the subgraph of the edges of weight ≥ `τ_w`; result set contained in WeLT's |
| WEGM | edge weights as pattern labels, Max method | reimplementation of Nguyen et al., J. Inf. Telecommun. 9(4):543–558, 2025; different result set |
| OWGraMi | vertex-bottleneck weight | reimplementation from the paper; different result set |
| EdgeTypeBound | same as WeLT | per-edge-type weight bound and exact weight check; used by the ablation variant `noTableBuild` |

WEGM maps edges to edges of equal weight, so its support counts equal-weight occurrences only; compare
its efficiency, not its patterns. The `WEGMStrategy` Javadoc states how three points left open by the
article are read. ReSuM's released code (built by `tools/build_resum.py`, not included) checks one edge
weight per matched vertex, so on cyclic patterns it differs from the ALL definition
(`results-probe/resum-cycle-check-20261019`); it is used in probes only.

## Build and test

Java 17 or later. Maven is optional: the scripts compile with `javac` and run the JUnit 5 console launcher,
which `build.sh` downloads to `tools/junit-console.jar` on its first run.

```bash
./build.sh                                # compile main and test sources
./test.sh                                 # fetch the test graphs, then run all tests
./run.sh datasets/citeseer.lg 500 GraMi   # one algorithm on one graph
```

`mvn test` also works after `python3 tools/fetch_datasets.py citeseer.lg email_eu.lg lastfm.lg`.

## Running one algorithm

`welt.sh` runs any class of `welt.runner` from any directory; with no argument it lists them.

```bash
./welt.sh MineMain      datasets/citeseer.lg 300 WeLT 95       # <dataset> <minSup> <algorithm> [minWeight]
./welt.sh CompareMain   datasets/citeseer.lg 200 96 800        # <dataset> <minSup> <minWeight> [budget]
./welt.sh BenchmarkMain datasets/email_eu.lg 10 336 0 60000    # <dataset> <minSup> <minWeight> <budget> <limitMs> [warmup] [measured]
```

`CompareMain` runs GraMi, EdgeTypeBound, WeLT and OWGraMi on one graph. Pass JVM flags through
`JAVA_OPTS`. A budget of 0 (used by every script) runs each MNI search to completion; a positive budget
makes MNI a lower bound and is recorded as `mode=approx`. In exact mode a run that aborts a search exits
with code 4.

## Data

| File | Vertices | Edges | Vertex labels | Edge weight | Source | In the repository |
|---|---|---|---|---|---|---|
| `citeseer.lg` | 3,312 | 4,536 | 6 | publication similarity, 0–100 | GraMi distribution, from CiteSeer (LINQS) | no, downloaded |
| `mico.lg` | 100,000 | 1,080,156 | 29 | co-authored papers | GraMi distribution | no, downloaded |
| `email_eu.lg` | 677 | 2,462 | 42 | emails, at least 25 | SNAP email-Eu-core (temporal edition, department labels) | no, rebuilt |
| `lastfm.lg` | 7,624 | 27,806 | 18 | Jaccard of node features ×100 | SNAP MUSAE LastFM Asia | no, rebuilt |
| `github.lg` | 37,700 | 289,003 | 2 | Jaccard of node features ×100 | SNAP MUSAE GitHub | no, rebuilt |
| `bitcoin_otc.lg` | 5,881 | 21,492 | 5 | trust rating +11, 1–21 | SNAP Bitcoin-OTC | no, rebuilt |
| `string_ecoli.lg` | 4,137 | 76,330 | 5 | STRING combined score, 500–999 | STRING v12.0, *E. coli* K-12, CC BY 4.0 | yes |
| `test1.lg`, `test2.lg` | 12 | 12, 9 | 3 | GraMi toy graphs | GraMi distribution | no, downloaded |
| `example.lg` | 6 | 8 | 3 | example of the paper | this repository | yes |

`python3 tools/fetch_datasets.py` downloads or rebuilds the graphs that are not committed, because their
sources state no licence that allows redistribution. Every downloaded file and every graph is checked
against its SHA-256, and the conversions (`datasets/convert_*.py`) are deterministic, so the rebuilt graphs
are byte-identical to those of the experiments. Sources, licences and requested citations are in
`datasets/NOTICE`.

CiteSeer edge values are almost all distinct, so GraMi reads them as edge labels and finds no pattern.
Here edges are unlabelled and the value is the weight.

## Experiment campaign

```bash
JAVA_OPTS="-Xmx48g" ./revision.sh all    # the whole campaign; run it again to continue
./revision.sh list                       # the steps
```

`prepare` creates `results/v2_revision/<date>_<host>/`, records the JDK and `JAVA_OPTS` in
`environment.lock`, checks the data and runs the tests; later steps refuse another JDK, other JVM flags or
a different git tree of `src/`. Each run directory holds one CSV per step, `meta_*.json` (machine, OS, JVM,
commit, clean tree), `topology.csv` and, in `patterns/`, the result set of each completed cell. With
`CHECKPOINT=1` each cell is committed, pushed to a campaign branch and reported to a status repository.
The campaign results are on the branches `campaign-*`, not on `main`. `experiments.sh` runs the earlier
scenarios `sc1`–`sc5` (`./experiments.sh list`).

Protocol:

- each (dataset, minSup, algorithm) cell runs in its own JVM: a probe run that is also the first warm-up,
  `WARMUP - 1` more warm-ups, then `MEASURED` runs (defaults 1 and 5); a probe that ends in TO or OOM is
  reported alone;
- the time limit is `TIME_LIMIT_MS` (default 60 min), checked inside every search;
- the clock covers the graph index, the preprocessing (the WeLT table: `tableBuildMs`) and mining; reading
  the `.lg` file is outside it;
- status is `OK`, `TO`, `OOM` (exit code 3) or `CRASH` (no row written); TO and OOM rows hold the counts
  reached and `partial=1`;
- the scripts refuse a modified working tree (`ALLOW_DIRTY=1` for trial runs), rebuild, and keep the machine
  awake (`tools/keep_awake.ps1` on Windows).

`BenchmarkMain` prints the CSV header. Its main columns: `N` generated patterns; `prunedP1`, `prunedP2`,
`prunedEdgeType`, `prunedP3` patterns pruned before support counting; `M` patterns whose support was
counted; `R` result patterns; `mniIsoCallsMine`, `weightIsoCalls`, `backtrackMni`, `backtrackWeight`
search cost; `tableBuildMs`, `mineMs`, `totalMs` (median, with `totalMinMs`, `totalMaxMs`, `totalSdMs`);
`visitedCodeChars`, `memoCodeChars`, `frontierDomainIntsPeak`, `tableKeptPatterns`, `tableDomainInts`
retained data counted from its definition; `liveHeapPeakMB` with `WELT_LIVE_HEAP=on`. `weightModel` is
`none` (GraMi), `edge` (GraMiFilter, EdgeTypeBound, WeLT: same result set), `edge-label` (WEGM) or `vertex`
(OWGraMi).

Settings read from the environment:

- `WELT_VOTER=α,β,γ` and `WELT_WORDER=on|off` set the matching order of WeLT (default `1,1,1`, `on`); the
  other algorithms use the degree-only order;
- `WELT_TABLE_REUSE=off` stops the miner from reusing the supports counted while building the table;
  `WELT_TABLE_THRESHOLD=off` stores the exact maximum weight of each 2-edge path instead of a yes/no
  answer for `τ_w`; neither changes the result set or the search (`TableCostOptionsTest`);
- `ABLATION_VARIANT` selects one variant of `AblationMain`: `full`, `noP1`, `noP2`, `noP2single`, `noP3`,
  `p3R`, `noWext`, `voteDeg`, `noWorder`, `noBnb`, `noTable`, `noTableBuild`, `noTableReuse`,
  `noTableThreshold`, `noTableOptions`; each completed variant must return the result set of `full`
  (exit code 5 otherwise);
- `DUMP_PATTERNS=<dir>` writes each result set (canonical code, size, exact weight);
  `tools/compare_patterns.py <dir> <out.csv> [A] [B]` compares two algorithms cell by cell.

`tools/min_heap.py` finds the smallest `-Xmx` at which a cell runs at most twice as slow as with the full
heap.

## Results of manuscript version 1

`results/v1_submission/` holds the results of the first manuscript, produced by commit `620017d` in
approximate mode; its README lists their limits. `tools/make_paper_figures.py` (Python 3, matplotlib,
numpy; also `tools/make_paper_figures.ipynb` on
[Colab](https://colab.research.google.com/github/tran-minh-thai/welt-weighted-fsm/blob/main/tools/make_paper_figures.ipynb))
redraws the figures of that version.

## Tests

`./test.sh` runs the whole suite. Among the tests:

- `GraMiOracleTest`: `GraMiStrategy` returns exactly the pattern sets of the original GraMi on unlabelled
  CiteSeer at minSup 500, 400 and 300 (`reference/oracle/`);
- `WeLTOracleTest`, `EdgeTypeBoundOracleTest`, `OWGraMiOracleTest`: each result set equals the GraMi set
  filtered by the weight condition;
- `WEGMPaperExampleTest`: `WEGMStrategy` reproduces the worked example of the WEGM article;
- `PivotInvarianceTest`, `PruneMechanismInvarianceTest`, `TableCostOptionsTest`: ordering, pruning and table
  options change the cost, never the result set;
- `CanonicalCodeGSpanTest`: the minimum DFS code gives the same isomorphism classes as a brute-force check.

## Citation and licence

Cite the software with [`CITATION.cff`](CITATION.cff). The code is released under the [MIT License](LICENSE).
