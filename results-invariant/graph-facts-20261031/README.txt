# How these files were made (commit 595e277, tree clean)
python3 tools/export_graph_facts.py results-invariant/graph-facts-20261031 --tau-w results-invariant/campaign-r2-numbers-20261030/numbers.json --self-test  (output: self_test.txt)
Inputs: datasets/<name>.lg for citeseer, mico, email_eu, lastfm, github, string_ecoli, verified by tools/fetch_datasets.py;
the weight threshold of each dataset from data.<ds>.tau_w of the campaign numbers.
Same keys and values as graph-facts-20261026, plus data.<ds>.{max_weight,max_weight_edge_share,rho_w,tau_w_percentile,tau_w_kept_share}
(over the edges the reader keeps: self-loops skipped, a repeated pair counted once with its first weight).
