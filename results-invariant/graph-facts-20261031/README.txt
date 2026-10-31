# How these files were made (commit 115ac16, tree clean)
python3 tools/export_graph_facts.py results-invariant/graph-facts-20261031 --self-test  (output: self_test.txt)
Inputs: datasets/<name>.lg for citeseer, mico, email_eu, lastfm, github, string_ecoli, verified by tools/fetch_datasets.py.
Same keys and values as graph-facts-20261026, plus data.<ds>.max_weight and data.<ds>.max_weight_edge_share
(over the edges the reader keeps: self-loops skipped, a repeated pair counted once with its first weight).
