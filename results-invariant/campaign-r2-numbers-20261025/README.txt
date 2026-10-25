# How these files were made (commit 6159862, tree clean)
python tools/export_campaign_numbers.py <campaign-revision-r2 checkout>/results/v2_revision/20261021_2047_Admin-PC results-invariant/campaign-r2-numbers-20261025/numbers.json
python tools/check_campaign_export.py <same campaign dir>  (output: check_campaign_export.txt)
campaign results: branch campaign-revision-r2, commit 3f9783f
