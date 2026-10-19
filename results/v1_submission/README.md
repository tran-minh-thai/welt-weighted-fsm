# Results of manuscript version 1

Produced by commit `620017d`; reproduce them from that commit. Column names follow that
commit (`freq` = R, `candMNI` = M, `WEGM` = EdgeTypeBound-ungated).

Limits:

- No record of the machine, operating system, JVM, heap limit (`-Xmx`) or commit per run.
- The search budget column shows that every cell ran in approximate mode (finite
  per-search budget), so MNI is a lower bound and `MaxW` may be underestimated.
- `limitMs` is 1800000 (30 min) for some timed-out cells and 3600000 (60 min) for others.
- WeLT time and peak heap exclude the lookup-table construction.
- Timed-out (`TO`) cells are a single probe run; out-of-memory was not distinguished
  from time-out.
