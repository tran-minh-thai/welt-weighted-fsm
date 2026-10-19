# Low-support count probe: EdgeTypeBound and WeLT

**Kind: probe.** Run on a development machine (see `meta.json`), one run per cell, 10-minute
limit, three datasets in parallel. Run times are not measurements and are not reported here;
the count columns are deterministic and hold on every machine because every cell completed.

- Code: commit `814404f` (the `src/` tree equals the one of the measurement campaign).
- Command: `run_cell` of `tools/common.sh` for EdgeTypeBound and WeLT, `-Xmx8g`, warmup 0,
  measured 1, limit 600000 ms; WeLT voter (1,1,1) with the weight-aware order.
- Files: `raw_<dataset>.csv` (runner output), `log_<dataset>.txt`, `patterns/` (result sets),
  `counts.csv` (count columns only, `python summarise.py`), `equivalence.csv`
  (`python ../../tools/compare_patterns.py patterns equivalence.csv EdgeTypeBound WeLT`).

| cell | R | M EdgeTypeBound | M WeLT | M WeLT incl. table | MNI calls EdgeTypeBound | MNI calls WeLT (mining / incl. table) | backtrack EdgeTypeBound | backtrack WeLT |
|---|---|---|---|---|---|---|---|---|
| citeseer 150 | 7 | 21 | 19 | 70 | 3724 | 3243 / 5332 | 15254 | 25287 |
| citeseer 100 | 9 | 28 | 22 | 89 | 3191 | 2871 / 4452 | 14854 | 23952 |
| email_eu 8 | 65 | 707 | 226 | 2492 | 4338 | 1700 / 3357 | 34105 | 45359 |
| email_eu 6 | 141 | 1673 | 450 | 4416 | 10123 | 3146 / 5997 | 96851 | 107411 |
| lastfm 400 | 19 | 54 | 50 | 127 | 45038 | 45044 / 50935 | 334107 | 363666 |
| lastfm 200 | 29 | 92 | 88 | 233 | 37595 | 36109 / 41568 | 760319 | 797928 |

tau_w: citeseer 96, email_eu 336, lastfm 40. Result sets of the two algorithms are identical in
all six cells (`equivalence.csv`). Every cell, for both algorithms, completed well within the limit.
