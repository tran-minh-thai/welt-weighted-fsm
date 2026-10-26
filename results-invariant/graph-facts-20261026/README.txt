Facts of the six input graph files that the reader resolves (LgGraphReader merges a pair listed in both
directions into one edge and keeps the weight of the line listed first; it skips self-loops).

  python3 tools/export_graph_facts.py results-invariant/graph-facts-20261026 --self-test

The graph files have the SHA-256 that tools/fetch_datasets.py checks (the files of the experiments).
Self-test: a changed weight on one repeated line and an added self-loop each change their count by one.
