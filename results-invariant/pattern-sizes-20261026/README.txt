Sizes of the patterns returned in the efficiency experiment (WeLT: the FWSs; GraMi: the frequent patterns),
from the pattern dumps of the r2 campaign (campaign-revision-r2, results/v2_revision/20261021_2047_Admin-PC/patterns).

  python3 tools/export_pattern_sizes.py <campaign dir> results-invariant/pattern-sizes-20261026 --self-test

The number of patterns in every dump equals the result count of its CSV row (the tool stops otherwise).
Counts of patterns, not times: they do not depend on the machine. Self-test: a dump with a line removed
and a dump with a changed edge count are both caught (2/2).
