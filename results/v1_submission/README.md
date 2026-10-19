# Results of the first submission (v1)

These five CSV files are the consolidated results reported in the first submission.
They were produced by commit `620017d` (and earlier commits of the same code line).
They are kept unchanged for comparison with the revision results.

Provenance limits (stated so these numbers are not mistaken for traceable runs):

- The files do not record the machine, the operating system, the JVM version, the heap
  limit (`-Xmx`) or the commit of each run. This cannot be reconstructed.
- The search budget column shows that every cell ran in approximate mode (finite
  per-search budget), so MNI is a lower bound and `MaxW` may be underestimated.
- `limitMs` is 1800000 (30 min) for some timed-out cells and 3600000 (60 min) for others.
- WeLT time and peak heap exclude the lookup-table construction.
- Timed-out (`TO`) cells are a single probe run; out-of-memory was not distinguished
  from time-out.

To reproduce these files, check out commit `620017d`, not the current code.
