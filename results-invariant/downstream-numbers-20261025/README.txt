# How these files were made (commit 2070e92, tree clean; Python with scikit-learn 1.9.1, the version of the run, which the export requires for the solver key)
# Earlier export at commit a73c7b8: the same values without the design.env.* keys.
python tools/downstream/export_numbers.py <campaign-downstream checkout>/results/v2_revision/20261024_1640_Admin-PC results-invariant/downstream-baseline-check-20261025 results-invariant/downstream-numbers-20261025/numbers.json results-probe/downstream/20261024_2000_mac-probe_github/downstream_choice.json
python tools/downstream/check_export.py <same campaign dir> results-invariant/downstream-baseline-check-20261025 results-probe/downstream/20261024_2000_mac-probe_github/downstream_choice.json  (output: check_export.txt)
campaign results: branch campaign-downstream, commit 1de985a
