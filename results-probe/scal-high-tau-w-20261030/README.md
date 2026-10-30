# Scalability group at a high tau_w: count probe for WeLT

**Kind: probe.** Run on a development machine (`meta.json`), one run per cell after one warm-up, a 10-minute
limit, six cells in parallel. Run times are not measurements and are left out; the count columns are
deterministic and hold on every machine for the cells that completed.

- Code: the measurement code of the r2 campaign (src tree `fb251a5`), voter (1,1,1) with the weight-aware order.
- Cells: GitHub at the 99th (40) and 99.9th (47.8261) percentiles of its edge weights; STRING E. coli at its
  maximum score (999) and at 990. At the supports used here (GitHub 24000 and 22000, STRING 1850 and 1800),
  GraMi, GraMiFilter and WeLT reach the 60-minute limit of the r2 campaign at the tau_w of the paper
  (GitHub 30, STRING 750), and GraMiFilter's search does not depend on tau_w.
- Files: `counts.csv` (count columns of the runner rows), `patterns/` (result sets of the completed cells).

| cell | tau_w | status | R | N | M | MNI-frequent seen |
|---|---|---|---|---|---|---|
| GitHub 24000 | 40 | TO (10 min) | 14 | 107 | 19 | 14 |
| GitHub 24000 | 47.8261 | OK | 4 | 16 | 9 | 5 |
| GitHub 22000 | 47.8261 | OK | 4 | 16 | 9 | 7 |
| STRING 1850 | 990 | OK | 21 | 339 | 29 | 25 |
| STRING 1850 | 999 | OK | 12 | 107 | 19 | 15 |
| STRING 1800 | 999 | TO (10 min) | 106 | 750 | 139 | 132 |

The four completed cells are the cells of `./revision.sh r5scal`, fixed from these counts before any
time was measured on the measurement machine.
