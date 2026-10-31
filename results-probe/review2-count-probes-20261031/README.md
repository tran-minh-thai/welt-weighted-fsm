# Count probes for the second review round

**Kind: probe.** Development machine (`meta.json`), one run per cell, no warm-up, a 10-minute limit. Run times are not
measurements and are left out; the count columns are deterministic and hold on every machine for completed cells.
A cell marked TO stopped at the 10-minute probe limit, not at the 60-minute limit of the campaign.

- `counts.csv`, group K1: email-Eu-core 10, 8, 6 at tau_w 336: full WeLT, weight-gated extension off (noWext), off
  together with P2 (noWextP2), and together with P1 and P2 (noWextP1P2), and GraMiFilter.
  At tau_s 10, noWextP1P2 has the N, M and P3-pruned counts of GraMiFilter.
- Group K3: WeLT-noTableBuild at the 27 efficiency configurations that WeLT completes and the 4 r5scal cells, in the
  order of the probe's run time (the order of the r6etb step). Its R, N, M and backtracking nodes equal the noTableBuild
  rows of the r2 campaign at the 15 cells both have.
- Correction: the first noWextP1P2 runs used a build that still carried a fault injected in a check of AblationMain
  (P1 left on). Those rows are replaced by runs of the committed build (`prunedP1` = 0).
