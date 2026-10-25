Check of every number of the running example (datasets/example.lg), at tau_s = 2 and tau_w = 5 or 7.

Command (from the repository root; --repo selects the source tree that is compiled and run):
  python3 tools/check_example.py --repo <checkout of campaign-revision-r2> --self-test --out results-invariant/example-check-20261025

Source run: the campaign-revision-r2 tree (miner_src_tree in run_meta.json), with the WeLT settings of
the benchmark (voter 1,1,1, weight-aware order, support reuse and threshold table on).
Counters are deterministic; they do not depend on the machine or the JVM.

check_example.txt lists 50 comparisons between three sources (definitions by brute force, the
level-wise search re-implemented from its description, and the miner) and the four injected faults.
