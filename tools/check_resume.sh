#!/usr/bin/env bash
# Self-check of resume and checkpoint (tools/common.sh). Uses temporary files and temporary
# git repositories only; nothing is pushed anywhere else. About one minute. Run from anywhere:
#   tools/check_resume.sh
set -euo pipefail
cd "$(dirname "$0")/.."
. tools/common.sh
export CHECKPOINT=0   # the checks below set CHECKPOINT=1 only against temporary repositories
[ -d target/classes ] || ./build.sh
T=$(mktemp -d)
fails=0
cases=0
check() { cases=$((cases + 1)); if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected $3, got $2"; fails=$((fails + 1)); fi; }
rows() { if [ -f "$1" ]; then echo $(( $(wc -l < "$1") - 1 )); else echo 0; fi; }

# --- a variant subset (ABLATION_ONLY) runs only the named variants; an unknown name is refused
SUB="$T/subset.csv"
ABLATION_ONLY="full noTableOptions" run_ablation_cell "$SUB" datasets/citeseer.lg 400 96 0 0 1 > /dev/null
check "ABLATION_ONLY runs exactly the named variants" "$(tail -n +2 "$SUB" | cut -d, -f2 | sort | tr '\n' ' ')" "full noTableOptions "
code=0; ( ABLATION_ONLY="full bogus" run_ablation_cell "$T/bad.csv" datasets/citeseer.lg 400 96 0 0 1 > /dev/null 2>&1 ) || code=$?
check "an unknown variant name is refused before any run" "$code:$(rows "$T/bad.csv")" "1:0"

# --- resume of single cells (CHECKPOINT unset: nothing is committed)
OUT="$T/cells.csv"
run_cell "$OUT" datasets/citeseer.lg 300 96 WeLT 0 0 1 > /dev/null
run_cell "$OUT" datasets/citeseer.lg 300 96 WeLT 0 0 1 > /dev/null
check "a finished cell is not run again" "$(rows "$OUT")" 1
WELT_VOTER="1,0,0" run_cell "$OUT" datasets/citeseer.lg 300 96 WeLT 0 0 1 > /dev/null
check "another voter is another cell" "$(rows "$OUT")" 2
WELT_ORDER_MODE=reverse run_cell "$OUT" datasets/citeseer.lg 300 96 WeLT 0 0 1 > /dev/null
WELT_ORDER_MODE=reverse run_cell "$OUT" datasets/citeseer.lg 300 96 WeLT 0 0 1 > /dev/null
check "an order mode is another cell, and is not run again" "$(rows "$OUT")" 3
check "the row of an order mode carries its label" "$(tail -1 "$OUT" | tr ',' '\n' | grep -c '^order:reverse$')" 1
JAVA_OPTS="-Xmx1k" run_cell "$OUT" datasets/citeseer.lg 300 96 GraMi 0 0 1 > /dev/null 2>&1
run_cell "$OUT" datasets/citeseer.lg 300 96 GraMi 0 0 1 > /dev/null
check "a recorded crash is not retried" "$(rows "$OUT")" 4

# --- resume of an ablation cell
ABL="$T/ablation.csv"
run_ablation_cell "$ABL" datasets/citeseer.lg 300 96 0 0 1 > /dev/null
first=$(rows "$ABL")
run_ablation_cell "$ABL" datasets/citeseer.lg 300 96 0 0 1 > /dev/null
check "a second ablation pass adds no row" "$(rows "$ABL")" "$first"
check "the first ablation pass wrote every variant" "$first" "$(java -cp target/classes welt.runner.AblationMain --variants | tr -d '\r' | grep -c .)"

# --- checkpoint and status line, against temporary repositories
git init -q --bare "$T/origin.git"
git init -q --bare "$T/status.git"
git clone -q "$T/status.git" "$T/status" 2>/dev/null
git -C "$T/status" config user.name check; git -C "$T/status" config user.email check@example.invalid
git -C "$T/status" commit -q --allow-empty -m init; git -C "$T/status" push -q origin HEAD 2>/dev/null
git init -q "$T/work"
(
    cd "$T/work"
    git config user.name check; git config user.email check@example.invalid
    git remote add origin "$T/origin.git"
    mkdir -p results; echo "a,b" > results/x.csv
    CHECKPOINT=1 CAMPAIGN_BRANCH=check-campaign RUN_STATUS_DIR="$T/status" STATUS_PROJECT=check COMPUTERNAME=M1 checkpoint "cell one" > /dev/null
    git remote set-url origin "$T/missing.git"
    echo "c,d" >> results/x.csv
    CHECKPOINT=1 CAMPAIGN_BRANCH=check-campaign RUN_STATUS_DIR="$T/status" STATUS_PROJECT=check COMPUTERNAME=M1 checkpoint "cell two" > /dev/null 2>&1
)
check "checkpoint pushed results to the campaign branch" \
    "$(git --git-dir="$T/origin.git" show check-campaign:results/x.csv 2>/dev/null | tail -n 1)" "a,b"
check "the status line reached the status remote" \
    "$(git --git-dir="$T/status.git" show HEAD:check/M1.log 2>/dev/null | grep -c 'cell one')" 1
check "a failed push is reported and the run goes on" \
    "$(grep -c 'cell two \[PUSH FAILED' "$T/status/check/M1.log" 2>/dev/null || echo 0)" 1
rm -rf "$T"
if [ "$fails" -eq 0 ]; then echo "check_resume: OK ($cases/$cases)"; else echo "check_resume: $fails of $cases check(s) failed" >&2; exit 1; fi
