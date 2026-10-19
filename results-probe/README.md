# Probe results (development machine)

Everything under this folder comes from a development machine. **Timings and memory figures here are a
feasibility probe and must not be reported**; only `revision.sh` on the measurement machine produces
reportable timings (under `results/v2_revision/`). Counts, pattern sets and classification metrics do
not depend on the machine.

| folder | content |
|---|---|
| `b6/20261019_1053_<host>/` | reference probe of the downstream experiment, run from a clean tree (`run_meta.json`) |
| `b6/20261019_1038_<host>/` | the same experiment on uncommitted code (see its `NOTE.md`); same metrics as the reference |
| `b6/20261019_1020_<host>/` | first probe, stopped by the first version of the selection rule (see its `NOTE.md`) |
| `b6/fault_injection_20261019.txt` | source mutations of the image-set export and of `tools/downstream/b6lib.py`, and the tests that catch them |
