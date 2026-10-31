# How these files were made (commit 133a46a, tree clean)
python tools/export_round5_numbers.py <campaign-round5 checkout>/results/v2_revision/round5_Admin-PC results-invariant/round5-numbers-20261031/numbers.json
python tools/export_round5_numbers.py --self-test <same campaign dir>  (output: self_test.txt)
campaign results: branch campaign-round5, commit c60d2c1; JDK lock equal to the r2 lock; src tree 006443e33f04b764630f49bd38dcbd4f2ed89c46

Key groups: r5.protocol.*, e3.<dataset>.{separate,shared.r5}.*, e3.shared.count.faster,
e3.shared.counter_condition, r5s.<dataset>.<minSup>.<tau_w>.<algorithm>.*, r5s.<dataset>.<minSup>.Q_at_least,
r5s.count.*, val.grami_public.*, val.grami_ours.*. The key list is in the docstring of the exporter.
