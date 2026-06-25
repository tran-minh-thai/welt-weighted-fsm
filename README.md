# SubWeightedGraphMiner (WeLT)

A shared engine plus pluggable strategies for a **fair** comparison of four popular
subgraph mining algorithms on a single large graph:

| Algorithm | Role | Code provenance |
|---|---|---|
| **GraMi** | unweighted baseline | *adapted* from the open-source code of Elseidy & Abdelhamid (PVLDB 2014) |
| **OWGraMi** | vertex weights | *reimplementation* from the paper (original code may not be public) |
| **WEGM** | edge weights, Max/Min operators | *reimplementation* from the paper |
| **WeLT** | **proposed** — bottleneck edge weights + double filter | new |

> Every algorithm shares ONE engine (`core/`) and a single MNI counter, so any
> difference in candidate count / runtime reflects the algorithm itself, not an
> implementation difference.

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
    MniSupportCounter      MNI counter via CSP (adapted from GraMi) — SHARED; arc consistency,
                           domain inheritance, search budget and time limit
    PivotVoter             multi-criteria extension-point ordering (α·domain + β·degree + γ·weight)
    MiningEngine           pattern-generation + edge-extension loop; anti-monotone closure pruning
    Metrics                CSV metrics: candidates, iso-calls, MINEmni, frequent count, time, mem
    WeightedCanonicalCode  O(1) dedup key for the lookup table (bottleneck model ⇒ = structural code)
    GraphIndex             shared index: frequent labels, edge triples, distinct weights
  strategy/
    MiningStrategy         plug-in point: prePrune / acceptFrequent / allowExtension
    GraMiStrategy          unweighted baseline: prunes by MNI only
    OWGraMiStrategy        vertex-weight bottleneck (different result set; efficiency comparison only)
    WEGMStrategy           edge-weight bottleneck with a per-edge upper-bound filter
    WeightedLookupTable    F_2 lookup table + MaxW(p); double filter P1 (structure) + P2 (UB_k < τ_w)
    WeLTStrategy           proposed: double filter + exact evaluation W(S) ≥ τ_w via G_{≥w}
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

`experiments.sh` runs all four algorithms on every dataset across a descending sweep of
support thresholds and aggregates the results into `results/experiments.csv`:

```bash
./experiments.sh                          # default: 60 s per-run time limit
TIME_LIMIT_MS=3600000 ./experiments.sh    # 1 hour limit per run
```

Each `(dataset, minSup, algorithm)` cell is timed with `BenchmarkMain`: warmup runs are
discarded and the **median** of `MEASURED` runs (default 5) is reported, because a single
timed run is unreliable (JIT/GC noise). The deterministic counting metrics (candidate
count, MNI iso-calls) come from one run. A run that exceeds the per-run wall-clock limit is
recorded as `TO` (timeout) instead of being waited on. Tune with the `TIME_LIMIT_MS`,
`WARMUP`, and `MEASURED` environment variables.

> **JVM memory:** no special flags are required for the committed datasets (all under
> 100K vertices), and **no `-Xss` is ever needed** — the backtracking recursion is only as
> deep as the pattern size. The default heap is 1/4 of physical RAM; on a low-RAM machine,
> or when mining the large graphs (`mico.lg`, `github.lg`) at low support, raise it via
> `JAVA_OPTS`, e.g. `JAVA_OPTS="-Xmx8g" ./experiments.sh`.
>
> **Staying awake:** a full run can take hours. On macOS the script already wraps itself in
> `caffeinate` so the machine will not idle-sleep mid-run (it is a no-op elsewhere); on
> Linux, prefix the command with `nohup` and/or disable suspend if running unattended.

The per-dataset support and weight thresholds (whose absolute scales differ across
datasets) are configured at the top of `experiments.sh`; report them as ratios
(σ_s = minSup / |V|, and ρ_w within each dataset's weight range) for cross-dataset
comparability.

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

## License

Released under the [MIT License](LICENSE).

The MNI support counter (`MniSupportCounter`) adapts the constraint-satisfaction
approach of GraMi (Elseidy, Abdelhamid, Skiadopoulos & Kalnis, *GraMi: Frequent
Subgraph and Pattern Mining in a Single Large Graph*, PVLDB 2014); please cite that
work as well when building on this code. The `OWGraMi` and `WEGM` strategies are
independent reimplementations from their respective papers.
