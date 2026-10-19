# WeLT — Weighted Frequent Subgraph Mining via a Lookup-Table Double Filter

[![tests](https://github.com/tran-minh-thai/welt-weighted-fsm/actions/workflows/test.yml/badge.svg)](https://github.com/tran-minh-thai/welt-weighted-fsm/actions/workflows/test.yml)
[![Java](https://img.shields.io/badge/Java-17%2B-blue)](https://adoptium.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

Reference implementation and experiment suite for the paper:

> **WeLT: Weighted Frequent Subgraph Mining in a Single Large Graph via a
> Lookup-Table Double Filter.** Tan-Dung Vo, Bao Huynh, Thai Tran.
> *Manuscript submitted for publication, 2026.*

A shared mining engine plus pluggable strategies for a **fair** comparison of
subgraph-mining algorithms on a single large graph:

| Algorithm | Problem solved | Role | Code provenance |
|---|---|---|---|
| **GraMi** | frequent subgraphs, no weights | unweighted reference | *adapted* from the open-source code of Elseidy & Abdelhamid (PVLDB 2014) |
| **EdgeTypeBound** | the same problem as WeLT (edge-bottleneck weight) | **direct baseline**: identical result set | per-edge-type weight bound + weight-gated extension, no lookup table |
| **EdgeTypeBound-ungated** | same as above | variant: no weight-gated extension | as above, but every frequent pattern is extended |
| **WEGM** | edge weights are pattern labels (Max method) | published edge-weight method; **different result set** | *reimplementation* of Nguyen et al., J. Inf. Telecommun. 9(4):543-558, 2025 |
| **OWGraMi** | vertex-bottleneck weight | vertex-weight method; **different result set** | *reimplementation* from the paper |
| **WeLT** | edge-bottleneck weight | **proposed** — lookup-table filters P1+P2, voting, bnb | new |

> **WEGM versus EdgeTypeBound.** In WEGM an edge weight is part of the pattern: an isomorphism
> must map edges to edges of equal weight (Definition 3(b) of the WEGM article), so support
> counts only equal-weight occurrences, and the weight of a pattern is computed from its own
> edges. `WEGMStrategy` mines the graph whose edge labels are the integer edge weights (the
> article's Citeseer rule) and prunes every edge below w (Max method). Its result set therefore
> differs from WeLT's by definition: compare its efficiency only. EdgeTypeBound solves WeLT's
> problem with the simple per-edge-type bound and is the baseline whose result set must equal
> WeLT's. The Javadoc of `WEGMStrategy` lists how three inconsistencies of the article are read.

> Every algorithm shares ONE engine (`core/`) and a single MNI counter, so any
> difference in candidate count / runtime reflects the algorithm itself, not an
> implementation difference.

## What WeLT proposes (1-minute version)

Subgraph isomorphism is NP-complete and dominates the cost of frequent-subgraph
mining. Existing weighted methods (WEGM, WeFreS, OWGraMi) prune branches by
**weight value** but still send every survivor through the (expensive) iso check.
WeLT inserts a **lookup-table double filter** *before* the iso check:

1. **(P1) Structural filter** — a precomputed table `F_k` of frequent k-edge
   patterns (default k=2). If any k-edge subpattern of a candidate is missing
   from `F_k`, the whole subtree is pruned in O(1).
2. **(P2) Weight filter** — each table entry also stores `MaxW(p)`. The bound
   `UB_k(S) = min MaxW(p)` dominates `W(S)` and every weight in the descendant
   subtree, so `UB_k(S) < τ_w` prunes the subtree by *anti-monotonicity of the
   bottleneck weight*.

The full algorithm adds two more pieces: a multi-criteria *matching-order
voting* that brings the weight signal into the matching variable order, and a
*weight branch-and-bound* inside backtracking that cuts partial embeddings
whose running bottleneck has already dropped below τ_w. A single
anti-monotonicity property of the bottleneck weight proves the whole pipeline
both **complete** and **correct**.

## Requirements & Build

- Java 17+ (tested on Java 26). Maven is *recommended* but **not required**:
  because the current environment lacks `mvn`, the project builds and tests directly
  with `javac` + standalone JUnit 5 (`tools/junit-console.jar`).

```bash
./build.sh        # compile main + test
./test.sh         # run the full test suite (one command) — equivalent to `mvn test`
./run.sh datasets/citeseer.lg 500 GraMi   # mine with GraMi, minSup=500
```

Once Maven is installed, `mvn test` also works (see `pom.xml`, target Java 17).

## Architecture

```
src/main/java/welt/
  core/
    LabeledWeightedGraph   undirected graph model, vertex labels + (edge label ⟂ weight)
    LgGraphReader          reads .lg; separates the "structural matching label" from the "weight"
    WeightAssigner         weight-assignment function (default = edge-label value)
    EdgeLabelAssigner      structural-label-assignment function (default: edges unlabeled)
    VertexWeightAssigner   vertex-weight derivation (for OWGraMi)
    Pattern, CanonicalCode pattern + minimum-DFS-code canonical form (gSpan-style dedup, any size)
    MniSupportCounter      MNI counter via CSP (adapted from GraMi) — SHARED across strategies;
                           arc consistency (AC-3), parent→child domain inheritance, multi-criteria
                           variable ordering, search budget and time limit
    PivotVoter             multi-criteria matching-order voting (α·domain + β·degree + γ·weight);
                           drives both the variable order in MNI counting and the start vertex
                           in the weight-embedding search
    MiningEngine           BFS pattern lattice + edge-extension loop (leaf and chord); full
                           anti-monotone closure pruning (P3) at level (k-1)
    Metrics                CSV metrics: candidates, iso-calls, MNI iso-calls, frequent count, time, mem
    WeightedCanonicalCode  O(1) dedup key for the lookup table (bottleneck model ⇒ = structural code)
    GraphIndex             shared index: frequent labels, edge triples, distinct weights
  strategy/
    MiningStrategy         plug-in point: prePrune / acceptFrequent / allowExtension
    GraMiStrategy          unweighted baseline: prunes by MNI only
    OWGraMiStrategy        vertex-weight bottleneck (different result set; efficiency comparison only)
    EdgeTypeBoundStrategy  edge-bottleneck baseline: per-edge-type weight bound, exact weight check,
                           optional weight-gated extension (EdgeTypeBound / EdgeTypeBound-ungated)
    WEGMStrategy           WEGM, Max method: weight-labeled graph, edges below w pruned
    WeightedLookupTable    F_k lookup table (k=2) with MaxW(p) computed exactly, plus the heaviest
                           edge per edge type; filters P1 (some k-edge subpattern not in F_k) and
                           P2 (UB_k < τ_w; for 1-edge patterns, heaviest edge of the type < τ_w)
    WeLTStrategy           proposed: double filter + exact weight check W(S) ≥ τ_w by embedding
                           into G_{≥τ_w} with weight branch-and-bound (Lemma "bnb" in the paper)
  runner/
    MineMain               run one strategy on one dataset
    CompareMain            run all four algorithms, print a CSV comparison
    RunHarness             one timed run: the clock covers index, preprocessing (lookup table) and
                           mining; status OK / TO / OOM with the counts reached at the stop
    BenchmarkMain          one cell: probe + warmup + measured runs (median, min, max, SD), CSV
    AblationMain           one-factor ablation of WeLT, result-set invariant by canonical code
    PatternDump            result sets with exact MNI and W (DUMP_PATTERNS)
    RunMetaMain            run provenance (machine, JVM, commit, clean tree) -> meta_*.json
    ...                    plus probe runners (AcCompareMain, WeltSweepMain, ...)
```

`welt.sh` launches any runner from **any working directory** (it switches to the project
root first, so `datasets/...` paths and `target/classes` always resolve — you do not need
to `cd` in or build by hand). Run it with no arguments to list the available runners.

```bash
./welt.sh MineMain      datasets/citeseer.lg 300 WeLT 95     # mine one strategy
./welt.sh CompareMain   datasets/citeseer.lg 200 96 800      # four-way comparison (+ CSV)
./welt.sh BenchmarkMain datasets/email_eu.lg  10 336 0 60000    # median timing + time limit
```

Argument order: `MineMain <dataset> <minSup> <algorithm> [minWeight]`,
`CompareMain <dataset> <minSup> <minWeight> [budget]`,
`BenchmarkMain <dataset> <minSup> <minWeight> <budget> <limitMs> [warmup] [measured]`.
Dataset paths are relative to the project root (e.g. `datasets/citeseer.lg`).
The `./run.sh` and `./experiments.sh` wrappers likewise work from any directory.

The pivot voter, the early-pruning mechanisms, the decremental domain inheritance, and
the search budget are all toggleable for ablation studies.

**Exact mode is the default.** The `budget` argument is 0 everywhere in the scripts: every MNI
embedding search runs to completion. A non-zero budget switches to approximate mode (MNI
becomes a lower bound), is recorded as `mode=approx`, and never applies to weight checks. An
exact-mode run that reports an aborted search stops with exit code 4. The results in `results/v1_submission/` used approximate mode (commit `620017d`).

## Data (`datasets/`)

| File | Vertices | Edges | Vertex labels | Edge weight | Source |
|---|---|---|---|---|---|
| `citeseer.lg` | 3,312 | 4,536 | 6 | publication similarity ∈[0,100] | native |
| `email_eu.lg` | 677 | 2,462 | 42 | number of emails (≥25), right-skewed | native (SNAP email-Eu-core) |
| `string_ecoli.lg` | 4,137 | 76,330 | 5 | STRING combined-score ∈[500,999] | native (STRING E. coli) |
| `bitcoin_otc.lg` | 5,881 | 21,492 | 5 | trust rating shifted to [1,21] | native (SNAP Bitcoin-OTC) |
| `lastfm.lg` | 7,624 | 27,806 | 18 | Jaccard of node features ×100 | derived (MUSAE LastFM) |
| `test1/2.lg` | — | — | — | GraMi toy graphs | — |

Two large graphs are not committed (regenerate them with the converters): `mico.lg`
(100K vertices, co-authored-paper edge labels) and `github.lg` (37,700 vertices, MUSAE
GitHub with Jaccard weights). The conversion scripts live in `datasets/`
(`convert_musae.py`, `convert_string.py`, `convert_bitcoin.py`, `convert_email.py`); each
prints the resulting size and weight statistics. Raw downloads go under `datasets/raw/`.

> **CiteSeer note:** the edge label is a near-unique real value (similarity). The original
> GraMi treats it as an *edge label*, so plain FSM returns 0 patterns. Here we treat edges
> as **unlabeled** (structural) and keep the real value as the **weight** — true to the
> spirit of weighted mining.
>
> **Where WeLT helps:** the lookup-table filter is most effective when the weight
> distribution is *selective* (high-weight subgraphs are rare) on a *sparse* graph — e.g.
> CiteSeer, email-Eu-core, LastFM. On graphs with a dense high-weight core (Bitcoin-OTC,
> high-confidence STRING) the filter engages less, which the comparison reports honestly.

## Running the full experiment

The comparison is split into independent **scenarios**. Every scenario of one campaign writes
into ONE run directory, together with its provenance:

```
results/v2_revision/<YYYYMMDD_HHMM>_<host>/
    SC<n>_<scenario>.csv           one row per (dataset, minSup, algorithm) cell
    meta_<datetime>_<command>.json machine, OS, CPU, RAM, JVM and its arguments, commit, clean tree, start/end
    topology.csv                   size, density, degree and weight statistics of every dataset
    patterns/                      result set of every completed cell (exact MNI and W)
```

Set `RUN_DIR=<that directory>` to add scenarios to an existing campaign. `results/v1_submission/`
holds the results of manuscript version 1 (see its README).

| Scenario | Algorithms | Datasets | Purpose |
|---|---|---|---|
| `sc1` efficiency | GraMi, EdgeTypeBound, WEGM, WeLT | citeseer, email_eu, lastfm | runtime and search space versus support |
| `sc2` scalability | GraMi, EdgeTypeBound, WEGM, WeLT | mico, github, string_ecoli | large or dense graphs |
| `sc3` vertex_generality | OWGraMi | citeseer, email_eu | framework generality (own τ_w, different result set) |
| `sc4` ablation | WeLT variants | citeseer, email_eu | one-factor ablation |
| `sc5` memory | GraMi, EdgeTypeBound, WEGM, WeLT | citeseer, email_eu | peak heap |

```bash
./experiments.sh list            # describe the scenarios
./experiments.sh sc1             # run scenario 1
./experiments.sh sc2 mico        # scenario 2, only datasets whose path matches "mico"
./experiments.sh ablation        # one-factor ablation
./experiments.sh memory          # peak memory
```

**Protocol.** Each `(dataset, minSup, algorithm)` cell runs in its **own JVM** (an
out-of-memory error or a crash cannot affect the next algorithm, and no algorithm inherits
another's JIT warm-up). `BenchmarkMain` makes a probe run (also the first warmup); a probe
that ends in TO or OOM is reported alone; otherwise `WARMUP - 1` further warmups and
`MEASURED` runs follow (defaults 1 and 5). The per-run limit is `TIME_LIMIT_MS` (default 60
minutes); the deadline is checked inside every search, so a run stops near it. The scripts
refuse to run from a modified working tree (`ALLOW_DIRTY=1` for trial runs only), always
rebuild, keep the machine awake and print the manual checklist.

**What the clock covers.** Reading the `.lg` file is outside (the same for every algorithm).
Inside: the graph index (`indexMs`), the algorithm's preprocessing (`prepMs`: the WeLT lookup
table, reported separately as `tableBuildMs`; the per-type weight maps of the baselines; the
weight relabeling of WEGM) and mining (`mineMs`). `totalMs` is their sum and is the reported
runtime. The peak heap is reset (after a `System.gc()`) right before the clock starts.

**Status.** `OK`, `TO` (time limit), `OOM` (OutOfMemoryError caught; exit code 3) or `CRASH`
(the JVM died without a row; written by the script). For TO and OOM the counting columns hold
the values reached at the stop and `partial=1`.

**Main CSV columns** (`BenchmarkMain`; the header is printed by the runner, never typed in a
script):

| Column | Meaning |
|---|---|
| `N` | distinct patterns generated (after canonical deduplication, before any filter); `visitedSize` equals it by construction |
| `prunedStrategy` = `prunedP1` + `prunedP2` + `prunedEdgeType` | pruned before MNI counting: WeLT table structural (P1), WeLT table weight (P2, of which `prunedP2Single` on 1-edge patterns), per-edge-type bound of EdgeTypeBound / WEGM / OWGraMi |
| `prunedP3` | pruned by the anti-monotone closure (some (k-1)-edge subpattern not frequent) |
| `M`, `Mtotal` | patterns whose MNI was counted: mining phase, and mining plus lookup-table construction |
| `Mprime`, `R` | patterns with MNI ≥ minSup; result patterns (`R` was `freq`) |
| `extended`, `frontierPeak`, `frequentSize` | patterns extended, largest frontier, size of the P3 memo |
| `mniIsoCallsMine`, `mniIsoCallsTotal`, `weightIsoCalls` | embedding searches of MNI counting (mining / total) and of weight checks |
| `tableSize`, `tableBuildCandidates`, `tableBuildMniIsoCalls`, `tableBuildWeightIsoCalls` | lookup-table cost (0 for the baselines) |
| `backtrack` = `backtrackMni` + `backtrackWeight` | backtracking nodes |
| `voter`, `worder`, `voterSource`, `voteMs` | matching-order configuration and voting time |
| `indexMs`, `prepMs`, `tableBuildMs`, `mineMs`, `totalMs`, `totalMinMs`, `totalMaxMs`, `totalSdMs` | phase medians; total median, min, max, sample SD |
| `peakMemMB`, `peakMemMaxMB` | peak heap: median and max over the measured runs |
| `mode`, `budget`, `budgetExceeded`, `deterministic` | exact/approximate mode; counts identical across runs |

**Matching order.** WeLT reads `WELT_VOTER=α,β,γ` and `WELT_WORDER=on|off` (default when unset:
`1,1,1` and `on`, recorded as `voterSource=default`). GraMi, EdgeTypeBound, WEGM and OWGraMi
use the degree-only pivot without weight-aware order.

**Ablation.** `AblationMain` measures, one JVM per variant (`ABLATION_VARIANT`), the variants
`full`, `noP1`, `noP2`, `noP2single`, `noP3`, `p3R` (P3 checks the result set instead of the
MNI-frequent set), `noWext`, `voteDeg`, `noWorder`, and the combination `noTable` (P1 and P2
off). Each differs from `full` in exactly one factor except `noTable`. Every completed variant
must return the result set of `full`, compared by canonical code (`invariant=OK`; exit code 5
otherwise). Phase timers (`canonMs`, `filterMs`, `mniMs`, `weightMs`) are recorded here only.

**Result sets.** With `DUMP_PATTERNS=<dir>` (on by default in `experiments.sh`) each completed
cell writes `<dataset>_<algorithm>_<minSup>_<minWeight>.tsv`: canonical code, vertices, edges,
exact MNI and exact W, computed after the timed runs. `tools/compare_patterns.py <dir> <out.csv>
[A] [B]` compares two algorithms cell by cell (exit 1 on a difference, 2 if nothing could be
compared).

**Reading the `weightModel` column:** `none` (GraMi), `edge` (EdgeTypeBound, WeLT: identical
result sets, the speed-up claim is WeLT versus EdgeTypeBound), `edge-label` (WEGM) and
`vertex` (OWGraMi): read only the performance metrics of the last two.

> **Measurement machine (Windows).** The scripts run under Git Bash. They keep Windows awake
> with `tools/keep_awake.ps1` (SetThreadExecutionState, Windows PowerShell 5.1) and print a
> checklist of what they cannot enforce: pause Windows Update, "High performance" power plan,
> plugged in with lid-close action "Do nothing", the repository folder excluded from Defender
> real-time scanning, cloud sync paused. Use the same `JAVA_OPTS` (for example `-Xmx48g`) for
> every scenario; it is recorded in `meta_*.json`.
>
> **No `-Xss` is needed:** the backtracking recursion is only as deep as the pattern size.
> `tools/check_run_cell.sh` checks the four cell outcomes (OK, OOM in the JVM, JVM killed by
> OOM, JVM that cannot start) in about a minute.

**Full campaign (`revision.sh`).** `JAVA_OPTS="-Xmx48g" ./revision.sh all` runs, in order:
`prepare` (campaign directory, JDK and `JAVA_OPTS` lock, dataset check, self-tests), `b2dev`
(choice of the voting weights on calibrated `bitcoin_otc` cells), `b2grid` (voting sensitivity),
`b4` (ablation), `b1` per dataset (efficiency), `b5` per dataset (τ_w sweep at the smallest
support where GraMi completes, a shared-table WeLT sweep, and a WeLT-only sweep at the smallest
support where WeLT completes) and `b7` (EdgeTypeBound = WeLT on every completed cell). Running
it again continues: finished steps and cells are skipped. With `CHECKPOINT=1` every cell is
committed, pushed to the branch `campaign-revision-r1` and reported to a status repository.
`./revision.sh list` describes each step.

The per-dataset thresholds are configured in the scenario definitions inside `experiments.sh`;
report them as ratios (σ_s = minSup / |V|, and ρ_w within each dataset's weight range) for
cross-dataset comparability.

## Reproducing the paper figures

The script `tools/make_paper_figures.py` regenerates the four data figures of the paper
(`Fig2_efficiency_comparison.pdf`, `Fig3_memory_comparison.pdf`, `Fig4_ablation_study.pdf`,
`Fig5_scalability_comparison.pdf`) from the values embedded at the top of the script
(which match `results/v1_submission/SC1`..`SC5_*.csv`). It needs
Python 3 + `matplotlib` + `numpy`:

```bash
python tools/make_paper_figures.py
```

The companion notebook `tools/make_paper_figures.ipynb` runs the same script on Google
Colab without any local setup. Open it directly via:

[![Open In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/tran-minh-thai/welt-weighted-fsm/blob/main/tools/make_paper_figures.ipynb)

The example graph used as Figure 1 of the paper is drawn with TikZ (source in
`Fig1_example_graph.tex` next to the manuscript), not from data.

## Correctness testing

The full suite runs with `./test.sh`. Highlights:

- `GraMiOracleTest` — `GraMiStrategy` reproduces EXACTLY the pattern set of the original
  GraMi at minSup = 500 / 400 / 300 (oracle outputs in `reference/oracle/unlab_s*.txt`,
  generated by the original GraMi on unlabeled CiteSeer).
- `WeLTOracleTest`, `EdgeTypeBoundOracleTest`, `OWGraMiOracleTest` — each strategy's result set
  equals the GraMi-frequent set post-filtered by the corresponding weight condition.
- `WEGMPaperExampleTest` — `WEGMStrategy` reproduces the worked example of the WEGM article
  (domains of Figures 3 and 4, weights of Figure 5, Max-method pruning at w = 2).
- `ExactModeTest`, `DeadlineTest`, `RunHarnessTest`, `SearchSpaceCountersTest`,
  `VotingConfigTest`, `AblationTest`, `PatternDumpTest` — exact mode, deadline inside searches,
  cost accounting, counter identities, voting configuration, one-factor ablation and the
  result-set invariant, exact MNI. Each was checked by injecting the fault it targets.
- `CanonicalCodeGSpanTest` — the minimum-DFS-code canonical form induces the same
  isomorphism classes as a brute-force check, and lifts the small-pattern size cap.
- `PivotInvarianceTest`, `PruneMechanismInvarianceTest` — the ordering and the early-pruning
  optimizations change performance only, never the result set.

## Implemented features

- [x] Shared MNI engine + `GraMiStrategy`, validated against the original GraMi oracle.
- [x] `WeLTStrategy`: F_2 lookup table, double filter P1/P2, exact bottleneck check via G_{≥w}.
- [x] `OWGraMiStrategy` (vertex weights), `EdgeTypeBoundStrategy` (WeLT's problem, simple bound) and
      `WEGMStrategy` (WEGM as published, Max method); `CompareMain` CSV export.
- [x] `PivotVoter` multi-criteria extension ordering; early-pruning mechanisms; ablation runners.
- [x] gSpan minimum-DFS-code canonical form (no small-pattern size cap).
- [x] MNI optimizations: arc consistency, domain-restricted backtracking, parent→child domain
      inheritance, hub-aware candidate enumeration.
- [x] Exact mode by default (approximate search budget optional); per-run time limit checked inside
      searches; OK / TO / OOM / CRASH status; run provenance; pattern export.

## Cite this work

A machine-readable [`CITATION.cff`](CITATION.cff) is provided; GitHub renders a "Cite
this repository" button on the sidebar from it. A BibTeX entry will be added here once
the accompanying paper appears in print.

When relying on the MNI counter, also cite the original GraMi paper (Elseidy,
Abdelhamid, Skiadopoulos & Kalnis, *GraMi: Frequent Subgraph and Pattern Mining in a
Single Large Graph*, PVLDB 2014), whose constraint-satisfaction approach is adapted in
`MniSupportCounter`. The `OWGraMi` and `WEGM` strategies are independent
reimplementations from their respective papers.

## Repository topics

For discoverability on GitHub, suggested topics (add via *Settings → Topics* or the
gear icon next to *About*):

```
frequent-subgraph-mining graph-mining weighted-graphs subgraph-isomorphism
mni-support graph-algorithms data-mining bottleneck-weight java research-code
```

## License

Released under the [MIT License](LICENSE).
