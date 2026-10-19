# ReSuM's ALL score against its definition and against WeLT

**Kind: probe.** Development machine (see `meta.json`), `-Xmx8g`, limit 600000 ms, ReSuM one warmup and one
measured run, WeLT one run. Run times are not measurements and are not reported here; the result sets of the
completed cells are deterministic.

- Command: `run.sh` (from the repository root). ReSuM is the authors' code at commit `38307b2`, built by
  `tools/build_resum.py`; its runs go through `tools/run_resum.py` with relevance threshold just below tau_w.
- `resum.csv`: one row per cell (status, result count). `patterns/`: result sets of ReSuM-ALL, of the ALL set
  computed from its definition (`ResumMain definition`: GraMi on the edges weighing at least tau_w) and of WeLT.
- `resum_vs_definition.csv`: ReSuM-ALL = ALL-definition. `resum_within_welt.csv`: ReSuM-ALL contained in WeLT.

| cell (dataset minSup tau_w) | ReSuM-ALL | ALL-definition | WeLT |
|---|---|---|---|
| citeseer 300 50 | 8 | 8 | 9 |
| citeseer 300 80 | 2 | 2 | 9 |
| citeseer 300 96 | 0 | 0 | 5 |
| email_eu 20 100 | 0 | 0 | 7 |
| email_eu 20 336 | 0 | 0 | 4 |
| email_eu 15 100 | 1 | 1 | 29 |
| email_eu 15 336 | 0 | 0 | 14 |
| email_eu 10 100 | 3 | 3 | 628 |
| email_eu 10 336 | 0 | 0 | 43 |
| citeseer 200 80 | TO | — | TO |
| lastfm 600 40 | TO | — | 14 |

ReSuM-ALL equals the ALL definition in the 9 completed cells (4 of them with a non-empty set) and is contained in
the WeLT result set in all 9.
