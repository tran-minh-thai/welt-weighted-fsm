Voting analysis: which part of the work the matching order can change, and how much it changes.
Counters only (deterministic); run times in runs.jsonl are probe values, not measurements.

Commands (from the repository root, branch voting-mechanism):
  python3 tools/voting_probe.py run results-invariant/voting-mechanism-20261025
  python3 tools/voting_probe.py regress results-invariant/voting-mechanism-20261025 <campaign-revision-r2 run dir>
  python3 tools/summarise_voting.py results-invariant/voting-mechanism-20261025

Cells (19), with a separate run of the order mode cost (19 runs, commit 1c72a64, fixed in the log before it ran): the 10 cells of the voting experiment; email-Eu-core tau_s 4 and 3 and LastFM Asia tau_s 100
at the tau_w of the efficiency experiment; CiteSeer 300 at tau_w 96 and 90, email-Eu-core 10 and 6 at
tau_w 4495.3 and 3998.6 (the two largest tau_w with |R| >= 1 on the sweep). Each cell: the 22 voters of
the grid (simplex step 0.2 plus 1,1,1) and the order modes index, reverse and random with seeds 1 to 5;
551 runs. The reading rule was fixed before the runs: ratio = backtracking of the MNI counts and weight
checks of the mining phase under the compared order / under the voter 1,1,1; floor 1.10.

The runs were made in two invocations on one machine: 145 runs at commit a75abc5 and 406 at 12d30e5
(the second adds tools/summarise_voting.py only); both have source tree 334722c and the same
voting_probe.py. The 19 runs of the order mode cost were a third invocation at commit 1c72a64 (source
tree with the cost mode added; the default path regressed again: 247 comparisons, 0 differences).
run_meta.json records the last invocation only. mico.lg was copied from another checkout
after checking its SHA-256 against tools/fetch_datasets.py.

Regression: 4699 comparisons, 0 differences (229 runs equal the counters N, M, R, backtracking nodes and
embedding searches of the campaign; the parts of the new counters add up to the miner totals in every run;
every cell returns one result set under all voters and modes). Faults injected in a copy of runs.jsonl
(a backtracking count, M, a result-set hash) are caught 3/3; an empty file fails.

numbers.json and summary.md are written by summarise_voting.py; the key list is in its docstring.

Re-summarised at commit a9b7cbe: 171 keys added (e5x.<cell>.single.<a_b_c>.{ratio,nodes.mni,nodes.weight} for the voters 1,0,0, 0,1,0 and 0,0,1 of the grid, 19 cells); no value changed; summary.md unchanged.
