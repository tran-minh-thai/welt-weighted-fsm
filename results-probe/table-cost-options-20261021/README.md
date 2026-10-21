# Lookup-table cost options of WeLT: count probe

**Kind: probe.** Development machine (see `meta.json`), one run per cell and combination, limit 600000 ms. Run times
are not measurements and are not reported; the counts are deterministic: the counts of the run with both options
off equal those of the measurement campaign (`results/v2_revision/20261019_2137_Admin-PC`, branch
`campaign-revision-r1c`) in 23 of 23 cells.

- Code: this commit; options `WELT_TABLE_REUSE` (the miner takes the 1- and 2-edge support results of the table
  build) and `WELT_TABLE_THRESHOLD` (the table stores the weight test at tau_w instead of exact MaxW values and
  answers that test during mining), both off by default.
- Cells: the 23 B1 cells at which WeLT completes in the measurement campaign, with the campaign tau_w.
- Files: `run.sh` (from the repository root), `raw.csv`, `log.txt`, `patterns/<reuse>_<threshold>/`, `counts.csv`
  (`python summarise.py`), `run_stdout.txt` (the per-cell checks below).

Result set, generated, counted, frequent, accepted and extended patterns, P1, P2 and P3 decisions and table size are
identical in the four combinations in 23 of 23 cells. Embedding searches over the 23 cells:

| option | before | after | removed |
|---|---|---|---|
| reuse: MNI searches | 744182 | 534941 | 28.1% |
| threshold: weight searches | 5621 | 1333 | 76.3% |
| both: all searches | 749803 | 536274 | 28.5% |
| both: backtracking nodes | 3962717 | 3264474 | 17.6% |

On mico, github and string_ecoli the reuse option halves the MNI searches exactly: the table build counted the
frequent single edges and the miner counted them again.
