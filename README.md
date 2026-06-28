# WeLT — Weighted Frequent Subgraph Mining via a Lookup-Table Double Filter

[![tests](https://github.com/tran-minh-thai/welt-weighted-fsm/actions/workflows/test.yml/badge.svg)](https://github.com/tran-minh-thai/welt-weighted-fsm/actions/workflows/test.yml)
[![Java](https://img.shields.io/badge/Java-17%2B-blue)](https://adoptium.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

Reference implementation and experiment suite for the paper:

> **WeLT: Weighted Frequent Subgraph Mining in a Single Large Graph via a
> Lookup-Table Double Filter.** Tan-Dung Vo, Bao Huynh, Thai Tran.
> *Manuscript submitted for publication, 2026.*

A shared mining engine plus pluggable strategies for a **fair** comparison of four
subgraph-mining algorithms on a single large graph:

| Algorithm | Role | Code provenance |
|---|---|---|
| **GraMi** | unweighted baseline | *adapted* from the open-source code of Elseidy & Abdelhamid (PVLDB 2014) |
| **OWGraMi** | vertex weights | *reimplementation* from the paper |
| **WEGM** | edge weights, Max/Min operators | *reimplementation* from the paper |
| **WeLT** | **proposed** — bottleneck edge weights + double filter (P1+P2) + bnb | new |

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
    WEGMStrategy           edge-weight bottleneck with a per-edge upper-bound filter
    WeightedLookupTable    F_k lookup table (k=2) with MaxW(p) computed exactly; double filter
                           P1 (structural — some k-edge subpattern not in F_k) + P2 (UB_k < τ_w)
    WeLTStrategy           proposed: double filter + exact weight check W(S) ≥ τ_w by embedding
                           into G_{≥τ_w} with weight branch-and-bound (Lemma "bnb" in the paper)
  runner/
    MineMain               run one strategy on one dataset
    CompareMain            run all four algorithms, print a CSV comparison
    BenchmarkMain          rigorous timing: warmup + N runs (median) with a per-run time limit
    ...                    plus ablation/probe runners (AblationMain, AcCompareMain, WeltSweepMain, ...)
```

`welt.sh` launches any runner from **any working directory** (it switches to the project
root first, so `datasets/...` paths and `target/classes` always resolve — you do not need
to `cd` in or build by hand). Run it with no arguments to list the available runners.

```bash
./welt.sh MineMain      datasets/citeseer.lg 300 WeLT 95     # mine one strategy
./welt.sh CompareMain   datasets/citeseer.lg 200 96 800      # four-way comparison (+ CSV)
./welt.sh BenchmarkMain datasets/email_eu.lg  10 336 400 60000  # median timing + time limit
```

Argument order: `MineMain <dataset> <minSup> <algorithm> [minWeight]`,
`CompareMain <dataset> <minSup> <minWeight> [budget]`,
`BenchmarkMain <dataset> <minSup> <minWeight> <budget> <limitMs> [warmup] [measured]`.
Dataset paths are relative to the project root (e.g. `datasets/citeseer.lg`).
The `./run.sh` and `./experiments.sh` wrappers likewise work from any directory.

The pivot voter, the early-pruning mechanisms, the decremental domain inheritance, and
the search budget are all toggleable for ablation studies.

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

The comparison is split into independent **scenarios** so it can be run in parts instead of
one long unattended session. The consolidated, paper-final results live under `results/`
with the stable names below; each new run writes its own timestamped CSV first
(`SC<n>_<YYYYMMDD_HHMMSS>_<name>.csv`, gitignored) and is merged into the canonical file
afterwards.

| Scenario | Algorithms | Datasets | Final CSV | Purpose |
|---|---|---|---|---|
| `sc1` efficiency | GraMi, WEGM, WeLT | citeseer, email_eu, lastfm | `results/SC1_efficiency.csv` | RQ1+RQ2 — at low support WeLT completes while baselines T.O. |
| `sc2` scalability | GraMi, WEGM, WeLT | mico, github, string_ecoli | `results/SC2_scalability.csv` | scales to large/dense graphs (per-candidate MNI dominates) |
| `sc3` vertex_generality | OWGraMi | citeseer, email_eu | `results/SC3_vertex_generality.csv` | framework generality — **different** (vertex-weight) result set with OWGraMi's **own** τ_w (≈ vertex-weight median); kept separate |
| `sc4` ablation | WeLT variants | citeseer, email_eu | `results/SC4_ablation.csv` | RQ4 — per-component contribution (lookup table / voting / weight-order) |
| `sc5` memory | GraMi, WEGM, WeLT | citeseer, email_eu | `results/SC5_memory.csv` | RQ3 — peak heap at completable supports |

```bash
./experiments.sh list            # describe the scenarios
./experiments.sh sc1             # run scenario 1 -> results/SC1_<datetime>_efficiency.csv
./experiments.sh sc2 mico        # run scenario 2, only datasets whose path matches "mico"
./experiments.sh all             # run sc1, sc2, sc3 sequentially
./experiments.sh ablation        # RQ4 ablation -> SC4 CSV
./experiments.sh memory          # RQ3 peak memory -> SC5 CSV
```

Each `(dataset, minSup)` cell is timed with `BenchmarkMain`: warmup runs are discarded and the
**median** of `MEASURED` runs (default 5) is reported, because a single timed run is unreliable
(JIT/GC noise). The deterministic counting metrics (candidate count, MNI iso-calls) come from
one run. The default per-run limit is **60 minutes** (`TIME_LIMIT_MS`); a run that exceeds it is
recorded as `TO`. Tune with `TIME_LIMIT_MS`, `WARMUP`, and `MEASURED`.

When a baseline times out at the default 30-minute budget, `rerun_to.sh` re-runs the timed-out
cells at an extended limit (e.g. 60 minutes) and writes a `RERUN_*.csv`; those rows have already
been merged into the canonical `SC1_efficiency.csv` and `SC2_scalability.csv` and are
distinguished by their `limitMs` column.

**Reading the results — the `weightModel` column.** Every CSV row carries a `weightModel` of
`none`/`edge`/`vertex` so non-comparable result sets are never mixed up when aggregating:

- `none` — GraMi: unweighted; its `#FWS` is the structural superset (an upper reference).
- `edge` — WEGM and WeLT: the **same** edge-bottleneck result set, so their `#FWS` must be
  **identical** (a built-in correctness cross-check); the speedup claim is **WeLT vs WEGM**.
- `vertex` — OWGraMi: a **different** vertex-bottleneck set; read only its performance metrics,
  never compare its `#FWS` with the others.

> **JVM memory:** no special flags are required for the committed datasets (all under
> 100K vertices), and **no `-Xss` is ever needed** — the backtracking recursion is only as
> deep as the pattern size. The default heap is 1/4 of physical RAM; on a low-RAM machine,
> or when mining the large graphs (`mico.lg`, `github.lg`) at low support, raise it via
> `JAVA_OPTS`, e.g. `JAVA_OPTS="-Xmx8g" ./experiments.sh sc2`.
>
> **Staying awake:** a full run can take hours. On macOS the script already wraps itself in
> `caffeinate` so the machine will not idle-sleep mid-run (it is a no-op elsewhere); on
> Linux, prefix the command with `nohup` and/or disable suspend if running unattended.
>
> **On a laptop:** keep it on AC power with the lid open — `caffeinate` prevents idle sleep
> but cannot override a lid-close suspend. Sustained load also thermal-throttles a laptop,
> which makes wall-clock *times* noisier; the deterministic *count* metrics (candidates, MNI
> iso-calls) are unaffected, and `BenchmarkMain` runs each scenario's algorithms back-to-back
> per configuration so their relative comparison stays fair under the same thermal state. For
> the cleanest absolute timings, use a well-cooled machine or run one scenario (or one dataset
> via the filter argument) at a time.

The per-dataset support and weight thresholds (whose absolute scales differ across
datasets) are configured in the scenario definitions inside `experiments.sh`; report them as
ratios (σ_s = minSup / |V|, and ρ_w within each dataset's weight range) for cross-dataset
comparability.

## Reproducing the paper figures

The script `tools/make_paper_figures.py` regenerates the four data figures of the paper
(`Fig2_efficiency_comparison.pdf`, `Fig3_memory_comparison.pdf`, `Fig4_ablation_study.pdf`,
`Fig5_scalability_comparison.pdf`) from the consolidated CSVs in `results/`. It needs
Python 3 + `matplotlib` + `numpy`:

```bash
python tools/make_paper_figures.py
```

The example graph used as Figure 1 of the paper is drawn inline with TikZ, not from data.

## Correctness testing

The full suite (run with `./test.sh`) is **56 tests, all green**. Highlights:

- `GraMiOracleTest` — `GraMiStrategy` reproduces EXACTLY the pattern set of the original
  GraMi at minSup = 500 / 400 / 300 (oracle outputs in `reference/oracle/unlab_s*.txt`,
  generated by the original GraMi on unlabeled CiteSeer).
- `WeLTOracleTest`, `WEGMOracleTest`, `OWGraMiOracleTest` — each strategy's result set
  equals the GraMi-frequent set post-filtered by the corresponding weight condition.
- `CanonicalCodeGSpanTest` — the minimum-DFS-code canonical form induces the same
  isomorphism classes as a brute-force check, and lifts the small-pattern size cap.
- `PivotInvarianceTest`, `PruneMechanismInvarianceTest` — the ordering and the early-pruning
  optimizations change performance only, never the result set.

## Implemented features

- [x] Shared MNI engine + `GraMiStrategy`, validated against the original GraMi oracle.
- [x] `WeLTStrategy`: F_2 lookup table, double filter P1/P2, exact bottleneck check via G_{≥w}.
- [x] `OWGraMiStrategy` (vertex weights) & `WEGMStrategy` (edge weights); `CompareMain` CSV export.
- [x] `PivotVoter` multi-criteria extension ordering; early-pruning mechanisms; ablation runners.
- [x] gSpan minimum-DFS-code canonical form (no small-pattern size cap).
- [x] MNI optimizations: arc consistency, domain-restricted backtracking, parent→child domain
      inheritance, hub-aware candidate enumeration.
- [x] Search-budget approximation + per-run time limit; `BenchmarkMain` (median over repeated runs).

## Cite this work

If you use this code or build on it, please cite the WeLT paper. A machine-readable
[`CITATION.cff`](CITATION.cff) is provided (GitHub renders a "Cite this repository"
button automatically). A BibTeX entry:

```bibtex

```

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
