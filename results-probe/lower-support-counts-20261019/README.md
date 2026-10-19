# Lower-support count probe: EdgeTypeBound and WeLT

**Kind: probe.** Run on a development machine (see `meta.json`), one run per cell, 10-minute
limit, three datasets in parallel. Run times are not measurements and are not reported here;
the count columns are deterministic and hold on every machine because every cell completed.

- Code: commit `3cb8063` (the `src/` tree equals the one of the measurement campaign).
- Command: `run_cell` of `tools/common.sh` for EdgeTypeBound and WeLT, `-Xmx8g`, warmup 0,
  measured 1, limit 600000 ms; WeLT voter (1,1,1) with the weight-aware order.
- Files: `raw_<dataset>.csv` (runner output), `log_<dataset>.txt`, `patterns/` (result sets),
  `counts.csv` (count columns only, `python summarise.py`), `equivalence.csv`
  (`python ../../tools/compare_patterns.py patterns equivalence.csv EdgeTypeBound WeLT`).
- `patterns/lastfm_EdgeTypeBound_100_40.0.tsv` comes from a rerun of that cell with the same code
  (`log_lastfm_EdgeTypeBound_100_redump.txt`); its count row equals the row in `raw_lastfm.csv`.

| cell | R | M EdgeTypeBound | M WeLT | M WeLT incl. table | MNI calls EdgeTypeBound | MNI calls WeLT (mining / incl. table) | backtrack EdgeTypeBound | backtrack WeLT |
|---|---|---|---|---|---|---|---|---|
| citeseer 50 | 12 | 39 | 22 | 131 | 2212 | 1805 / 3108 | 13932 | 25344 |
| citeseer 20 | 14 | 51 | 32 | 177 | 1197 | 842 / 2081 | 15333 | 45238 |
| email_eu 4 | 601 | 9390 | 2750 | 9751 | 61726 | 19973 / 24838 | 807161 | 414019 |
| email_eu 3 | 3089 | 75910 | 25692 | 34555 | 543398 | 194750 / 201216 | 8738911 | 2836594 |
| lastfm 100 | 69 | 305 | 264 | 505 | 68389 | 59741 / 63849 | 4486526 | 4213259 |
| lastfm 50 | 150 | 1003 | 895 | 1564 | 110767 | 98340 / 102229 | 1041702730 | 1039930364 |

tau_w: citeseer 96, email_eu 336, lastfm 40. Result sets of the two algorithms are identical in
all six cells (`equivalence.csv`). Every cell, for both algorithms, completed within the limit.
