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
    Pattern, CanonicalCode pattern + isomorphism-invariant canonical code (deduplication)
    MniSupportCounter      MNI counter via CSP (adapted from GraMi) — SHARED
    MiningEngine           pattern-generation + edge-extension loop
    Metrics                CSV: candidateCount, isoCallCount, frequentCount, time, mem
    WeightedCanonicalCode  O(1) dedup key + lookup table (bottleneck model ⇒ = structural code)
    GraphIndex             shared index: frequent labels, edge triples, distinct weights
  strategy/
    MiningStrategy         plug-in point: prePrune / acceptFrequent / allowExtension
    GraMiStrategy          baseline: prunes by MNI only
    WeightedLookupTable    F_2 lookup table + MaxW(p); double filter P1 (structure) + P2 (UB_k < τ_w)
    WeLTStrategy           proposed: double filter + exact evaluation W(S) ≥ τ_w via G_{≥w}
  runner/
    ReadDatasetMain        Milestone 1: read + dataset statistics
    MineMain               run one strategy (GraMi | WeLT)
```

Run WeLT (4th argument = `minWeight`):
```bash
./run.sh datasets/citeseer.lg 300 WeLT 95
```

Pivot voting and branch-and-bound are toggled via flags (Milestone 5, ablation).

## Data (`datasets/`)

| File | Vertices | Edges (undirected) | Edge weight |
|---|---|---|---|
| `citeseer.lg` | 3,312 | 4,536 | similarity ∈[0,100] |
| `mico.lg` | 100,000 | 1,080,156 | co-authored-paper count (106 integer labels) |
| `test1/2.lg` | — | — | GraMi toy graphs |

`datasets/raw/` holds the raw SNAP/MUSAE sources (Facebook ego, LastFM Asia, GitHub
Social) — **not yet weighted**; the converters to `.lg` are run in the data-preparation milestone.

> **CiteSeer note:** the edge label is a near-unique real value (similarity). The original
> GraMi treats it as an *edge label*, so plain FSM returns 0 patterns. Here we treat edges
> as **unlabeled** (structural) and keep the real value as the **weight** — true to the
> spirit of weighted mining.

## Correctness testing

`reference/GraMi/` is the original GraMi code (cloned, built) used as an **oracle**.
`reference/oracle/unlab_s*.txt` are the FSM outputs of the original GraMi on CiteSeer
(unlabeled edges). The regression test `GraMiOracleTest` asserts that `GraMiStrategy`
produces EXACTLY the same set of patterns (compared by canonical code) at
minSup = 500 / 400 / 300 → **must always be green**.

## Milestone status

- [x] **Milestone 1** — data reading (`LgGraphReader`, statistics).
- [x] **Milestone 2** — shared engine + `GraMiStrategy`, validated against the original GraMi oracle.
- [x] **Milestone 3** — `WeLTStrategy` (k=2): lookup table, double filter P1/P2, weight check via G_{≥w}. Validated against the GraMi+weight-postfilter oracle (24/24 tests green + an independent Python cross-check).
- [ ] **Milestone 4** — `OWGraMiStrategy` & `WEGMStrategy`, export a CSV comparison of all four algorithms.
- [ ] **Milestone 5** — pivot voting + branch-and-bound (ablation); general k (gSpan DFS code); more datasets.
