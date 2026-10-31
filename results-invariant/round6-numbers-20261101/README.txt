# How these files were made (commit dfe2a89, tree clean)
python tools/export_round6_numbers.py <campaign-round6 checkout>/results/v2_revision/round6_Admin-PC <campaign-revision-r2 checkout>/results/v2_revision/20261021_2047_Admin-PC <campaign-round5 checkout>/results/v2_revision/round5_Admin-PC results-invariant/round6-numbers-20261101/numbers.json
python tools/export_round6_numbers.py --self-test <same three directories>  (output: self_test.txt)
campaign results: branch campaign-round6, commit 679b570; JDK lock equal to the r2 lock; src tree 1a41c427d425c54b1b23f338cee7342568b92bab
(code b7bb404); r2: campaign-revision-r2 3f9783f; round 5: campaign-round5 c60d2c1

Key groups: r6.protocol.*, e2.k1.<variant>.email_eu.<tau_s>.*, e2.k1.rows, e1.cell.<ds>.<tau_s>.noTableBuild.*,
r5s.<ds>.<tau_s>.<tau_w>.noTableBuild.*, {e1,r5s}.ntb.cells, {e1,r5s}.count.welt_vs_ntb.{faster,slower,tie},
{e1,r5s}.ntb_over_welt.ratio.{min,max}, e1.ntb.result_sets_compared. The key list is in the docstring of the exporter.
The WeLT times compared with WeLT-noTableBuild come from the r2 and round-5 sessions (same machine and JDK, another day).
