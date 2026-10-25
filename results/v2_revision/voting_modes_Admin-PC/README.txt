Run times of WeLT under the order modes of the voting analysis (revision.sh b2modes), measured on the
measurement machine (environment.lock: the JDK and heap of the r2 campaign; src.lock: the source tree of the
branch voting-mechanism). Copied from the branch campaign-voting-modes (commit ced124f), where the result
set of every row is kept under patterns/.

120 rows, all OK, 5 measured runs each after 1 warmup. The counters of every row (N, M, R, backtracking
nodes, embedding searches) equal those of the counter runs in results-invariant/voting-mechanism-20261025
(720 comparisons, 0 differences).

summary/ is written by
  python3 tools/summarise_order_times.py results/v2_revision/voting_modes_Admin-PC/B2_order_modes.csv \
      results-invariant/voting-mechanism-20261025/numbers.json results/v2_revision/voting_modes_Admin-PC/summary \
      <campaign-revision-r2 run dir>
The rule (fixed before the runs): the tie rule of the efficiency experiment and the 1.10 floor of the
ablation. 108 compared orders, all ties. The phase shares come from the full WeLT rows of the ablation
files of the r2 campaign (phase timing on). Injected faults (a compared run made twice as slow, or half as
long, with a small spread) change exactly one verdict each.
