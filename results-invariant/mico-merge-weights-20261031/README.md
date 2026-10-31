# MiCo: does the weight kept for a pair listed twice change a result?

**Kind: invariant.** Counts and result sets only; they do not depend on the machine.

`mico.lg` lists 142 vertex pairs twice, 138 of them with two different weights. The reader keeps the first
listing. `tools/merge_weights.py` writes two copies in which each such pair keeps its larger
(`mico_merge_max.lg`, 40 first listings change) or its smaller weight (`mico_merge_min.lg`, 98 change).

Cells, at tau_w = 100: tau_s 16000 and 15000 (scalability table; 15000 is also the MiCo cell of the voting
table) and 14826 (nesting table: WEGM, ALL, WeLT). Algorithms: GraMi, GraMiFilter, WEGM, WeLT, and the ALL
definition at 14826.

- `counts.csv`: status, R, N, M and backtracking nodes per graph, cell and algorithm (39 rows).
- `patterns/`: the result sets. Every file of a copy is byte-identical to the file of `mico.lg`.
- `numbers.json` (`summary.txt`): changed cells 0 of 3 for each copy.

Why nothing changes here: no pair with two weights touches a vertex of label 25, and every pattern in the
result sets of these cells has only label-25 vertices (`data.mico.merge.pairs_on_result_labels` = 0). The
statement covers these cells only; at a lower support a pattern with other labels could use such a pair.
`tools/summarise_merge_probe.py` counts a cell as changed when any count or result-set file differs; an
increased backtracking count and an edited result-set file were each counted (1 of 1).
