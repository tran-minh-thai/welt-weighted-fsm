# email-Eu-core at tau_s = 10, tau_w = 336: the 43 patterns of WeLT, one row each

**Kind: invariant.** Counts only, recomputed from the definitions by `tools/case_study.py` (standard library,
no code shared with the Java miner). They hold on every machine.

- `patterns/email_eu_WeLT_10_336.0.tsv`: the result set written by `BenchmarkMain` (`DUMP_PATTERNS`).
- `patterns.csv`: one row per pattern. Vertex labels are the department ids of the dataset. Pattern vertex
  `i` of `vertex_labels`, `images_G`, `images_ge` and `heaviest_vertices` is vertex `i` of the canonical code;
  `pattern_edges` lists the pattern edges in the order of `heaviest_edge_weights`.
  - `mni_G`: minimum of `images_G` (images of each pattern vertex over all embeddings in G).
  - `W`: maximum over embeddings of the lightest edge; `W_dump` is the value of the miner.
  - `all_score`: minimum of `images_ge` (images over embeddings in G restricted to edges of weight >= 336),
    the support under the ALL criterion.
  - `embeddings`: injective label-preserving maps; `occurrences`: distinct edge sets.
  - `heaviest_*`: one embedding whose lightest edge equals `W` (the smallest vertex tuple among ties);
    vertex ids are those of the `.lg` file.
- `check.txt`: the checks of the run: W equal to the miner's for 43/43, `mni_G >= 10` and `W >= 336` for
  43/43, `all_score >= 10` for 0/43 (ALL keeps none of the 43). The self-test (`--self-test`, 10 cases on a
  hand-made graph) catches each of three injected faults: `>=` changed to `>`, reverse-edge merging off,
  injectivity off.
