# Brute-force oracle against WeLT

**Kind: invariant.** Result sets and counts; they hold on every machine.

`tools/bruteforce_oracle.py` (Python standard library, no code shared with the Java miner) computes the result
set from the definitions on 30 small graphs (8 written for the cases below, 22 seeded random ones with 5 to 8
vertices, up to 11 edges, 1 to 3 labels and weights in {0, 1, 2, 3, 5}): every connected edge subset, a
canonical form over all label-preserving vertex orders, MNI and W from all embeddings, and W a second time
from the edge subsets. Each graph is run at tau_s in {1, 2, 3} and tau_w at every distinct weight of the graph
(so weights equal to the threshold occur) and one value above the largest weight.

Cases written for the check: repeated vertex labels, symmetric patterns (triangle, star), weights equal to
the threshold and weight 0, cycles with a chord, a disconnected graph, one heavy occurrence among light ones,
a pattern reachable from several parents, cycles whose vertices are shared with other patterns.

- `oracle_counts.json`: 30 graphs, 432 (tau_s, tau_w) pairs, 5434 patterns compared (union per pair), 0 false
  positives, 0 false negatives, 0 differences in W.
- `faults.txt`: seven faults put into a copy of the miner, each caught: an edge of weight exactly tau_w
  rejected, an edge lighter than tau_w accepted, MNI off by one in each direction, P2 on one-edge patterns at a
  tie, the threshold table recording a path only above tau_w, and no cycle-closing extension (so the edge
  removal lemma no longer holds for the generated patterns). An eighth candidate, `ub <= minWeight` in the
  2-edge part of P2, is not a fault: the threshold table stores only plus or minus infinity, so the change
  does not alter any comparison; the oracle reports no difference for it.
- `self_test.txt`: 9 hand-worked checks of the oracle itself.
- CI runs the self-test and the comparison after the unit tests (about 2 minutes); the fault check is run
  by hand (`tools/check_bruteforce_faults.py`, about 5 minutes with three jobs).
