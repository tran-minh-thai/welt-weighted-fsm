# How these files were made (commit 57b61e6, tree clean)
python tools/export_campaign_numbers.py <campaign-revision-r2 checkout>/results/v2_revision/20261021_2047_Admin-PC results-invariant/campaign-r2-numbers-20261030/numbers.json
python tools/check_campaign_export.py <same campaign dir>  (output: check_campaign_export.txt)
campaign results: branch campaign-revision-r2, commit 3f9783f

Compared with campaign-r2-numbers-20261025: the 4882 earlier keys keep their values except 10
keys e2.b4low.lastfm.100.<variant>.verdict_one_jvm (faster -> tie: the single-JVM run was faster
while no search counter fell; the reported verdict of these cells comes from the three-JVM passes
and is unchanged); 240 keys are added. Backtracking nodes now count as a search counter, so the
ablation marks of the branch-and-bound variant follow the rule stated for every comparison.

Re-exported at commit 57b61e6: 8 keys added (e2.<table>.cell_list, e6.all_in_welt.by_exp.*, e6.all_in_welt.equal_cells); no value changed.
