# How these files were made (commit a045159, tree clean)
python tools/downstream/export_numbers.py <campaign-downstream checkout>/results/v2_revision/20261024_1640_Admin-PC results-invariant/downstream-baseline-check-20261025 results-invariant/downstream-numbers-20261025/numbers.json
python tools/downstream/check_export.py <same campaign dir> results-invariant/downstream-baseline-check-20261025  (output: check_export.txt)
campaign results: branch campaign-downstream, commit 1de985a
