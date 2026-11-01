# Spread of the measured run times

delta/t per timed cell, where t is the median and delta the sample standard deviation of the 5 measured runs
(BenchmarkMain columns totalMs and totalSdMs). Written by `tools/export_time_spread.py`; inputs, hashes and
commands in `meta.json`.

- Experiment 1: 104 cells, the 77 completed cells of GraMi, GraMiFilter, WEGM and WeLT in the r2 campaign and the
  27 of WeLT-noTableBuild in the round-6 campaign.
- Higher tau_w (r5s): 12 cells, WeLT and WEGM of round 5 and WeLT-noTableBuild of round 6.
- Experiment 3: 60 cells, the GraMiFilter and WeLT rows of `B5_tau_sweep.csv` (the series the tie rule compares);
  each median equals the exported one. The shared-table sweep records medians only and has no spread.
