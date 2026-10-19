# First probe run: stopped by the first version of the selection rule

This run used the first version of the threshold scan, in which any selection run that did not
complete stopped the scan. The cell tau_s=1200, rho_w=0.5 (tau_w=28.772) reached the 10-minute
limit with 2,374 accepted patterns, so the scan stopped without a choice (`selection.json`).

The rule was revised: the patterns accepted before a time limit are true results, so 2,374 is a
lower bound on |R| and already decides "above the 20-300 window"; the scan then continues with
rho_w=0.7 and skips the lower tau_s at rho_w=0.5 (`b6lib.scan`). The run is kept as the record
of that revision. Its timings are a probe (development machine).

`console.txt` was written as `run.log` and renamed afterwards, because the repository ignores `*.log`; `run_meta.json` lists the original name.
