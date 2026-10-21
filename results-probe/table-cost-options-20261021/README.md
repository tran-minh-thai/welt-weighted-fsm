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

## Against EdgeTypeBound (counts)

`python compare_edgetypebound.py` compares the WeLT counts of this probe with the EdgeTypeBound counts of the
campaign B1 file (`origin/campaign-revision-r1c`) and writes `compare_edgetypebound.txt`. It stops unless the run
with both options off reproduces the campaign counts of WeLT; it does in 23 of 23 cells. One cell (string_ecoli
2000) has no embedding search in either method and is left out, so 22 cells compare.

| WeLT / EdgeTypeBound, geometric mean over 22 cells | options off | options on |
|---|---|---|
| embedding searches | 1.430 | 0.979 |
| backtracking nodes | 1.560 | 0.935 |

With both options on, WeLT does fewer embedding searches than EdgeTypeBound in 4 cells (email_eu 6 to 12, down to
0.535), the same number in 5 (github, mico, string_ecoli 1900) and more in 13 (citeseer 1.07 to 1.23, email_eu 15
and 20, lastfm by 6 to 12 searches). These are counts; whether the time verdict changes is measured by the campaign
of `revision.sh`, where B1 runs WeLT with both options on.
