# When can WEGM return a pattern?

**Kind: property of the data** (independent of the machine and of the Java code).

In WEGM's model an edge weight is part of the pattern (its integer part here, for every dataset), and
the support counts only occurrences with the same weights. Every edge of a frequent pattern is a frequent
1-edge pattern, and the Max method keeps only patterns whose edges all weigh at least τ_w. Hence WEGM
returns a pattern if and only if some weighted edge type (label a, label b, integer weight) with weight
≥ τ_w has MNI ≥ minSup.

`wegm_single_edges.py` computes, from the `.lg` files alone, the largest such MNI per dataset
(`output.txt`), then compares the criterion with the WEGM result count `R` of every completed cell of a
B1 file:

```
git show 0f7de14:results/v2_revision/20261019_2137_Admin-PC/B1_efficiency.csv > /tmp/B1.csv
python results-invariant/wegm-single-edge-support-20261021/wegm_single_edges.py /tmp/B1.csv
```

| dataset | distinct integer weights | τ_w | weighted edge types ≥ τ_w | largest MNI among them | smallest B1 minSup |
|---|---|---|---|---|---|
| citeseer | 65 | 96 | 11 | 5 | 100 |
| email_eu | 378 | 336 | 140 | 2 | 6 |
| github | 65 | 30 | 87 | 5352 | 24000 |
| lastfm | 55 | 40 | 46 | 12 | 200 |
| mico | 106 | 100 | 2531 | 14826 | 14000 |
| string_ecoli | 500 | 750 | 2601 | 345 | 1850 |

The criterion agrees with `R` in 25 of 25 WEGM cells of commit `0f7de14` (campaign branch
`campaign-revision-r1c`): WEGM returns no pattern in 24 cells and one pattern at mico 14000, the only cell
whose minSup is below the largest MNI. The comparison exits 1 when one count is changed (mico 14000,
R = 0).
