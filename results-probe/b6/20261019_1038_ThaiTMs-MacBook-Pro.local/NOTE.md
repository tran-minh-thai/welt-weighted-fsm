# Second probe run: uncommitted code

This run executed the B6 tools before they were committed (`run_meta.json`: `tree` MODIFIED,
`git_commit` is the parent commit); `tool_sha256` records the exact files that ran. It is kept as
the first end-to-end run of the revised selection rule. Two differences from the committed code:
`mining.csv` lacks the `limitMs` column (it was wrongly treated as a timing column and appears in
`timings_probe.json` instead), and the self-test counted only failed assertions as failures. The
probe run made from the committed code is the reference. Timings are a probe (development machine).

`console.txt` was written as `run.log` and renamed afterwards, because the repository ignores `*.log`; `run_meta.json` lists the original name.
