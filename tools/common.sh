#!/usr/bin/env bash
# Helpers shared by the experiment scripts. Source it: . "$(dirname "$0")/tools/common.sh"
#
# On Windows, Java and Python end their output lines with CR LF. Every value read back from
# them goes through `tr -d '\r'`, and result files are written with LF only.

# append_csv <runner-output-file> <csv-file>
# Copies the "CSV," lines printed by a runner into <csv-file>. The header is taken from the
# runner's own output (never typed in a script). A new file gets the header; an existing
# file must carry the identical header, otherwise the script stops instead of mixing
# column layouts.
append_csv() {
    local tmp="$1" out="$2" hdr
    hdr=$(grep -m1 '^CSV,dataset,' "$tmp" | sed 's/^CSV,//' | tr -d '\r' || true)
    if [ -z "$hdr" ]; then
        echo "WARNING: no CSV header in the runner output; nothing appended to $out" >&2
        return 0
    fi
    if [ ! -s "$out" ]; then
        echo "$hdr" > "$out"
    elif [ "$(head -1 "$out")" != "$hdr" ]; then
        echo "ERROR: CSV header of the runner differs from the header of $out" >&2
        echo "  runner: $hdr" >&2
        echo "  file:   $(head -1 "$out")" >&2
        exit 1
    fi
    grep '^CSV,' "$tmp" | grep -v '^CSV,dataset,' | sed 's/^CSV,//' | tr -d '\r' >> "$out" || true
}

# run_cell <out.csv> <dataset.lg> <minSup> <minWeight> <algorithm> <limitMs> <warmup> <measured> [budget]
# Runs ONE algorithm on one cell in its own JVM (an OutOfMemoryError or a crash cannot
# affect the next algorithm) and appends its CSV row. Outcomes:
#   exit 0 / 3   the runner printed its row (status OK, TO or OOM) -> appended as is;
#   no row      the JVM died before printing: the script writes a row with status OOM when
#                the output mentions OutOfMemoryError, CRASH otherwise (other columns empty);
#   exit 4      exact-mode violation -> the whole script stops.
run_cell() {
    local out="$1" ds="$2" s="$3" tw="$4" algo="$5" lim="$6" wu="$7" me="$8" budget="${9:-0}"
    local tmp code=0 status voter
    voter=$(expected_voter "$algo")
    if [ "${RESUME:-1}" = "1" ] && cell_done "$out" "dataset=$(basename "$ds")" "algorithm=$algo" "minSup=$s" "minWeight=$tw" "voter=$voter"; then
        echo "--- skip (already in $(basename "$out")): $algo $ds minSup=$s tau_w=$tw voter=$voter"
        return 0
    fi
    tmp=$(mktemp)
    echo ">>> $algo  $ds  minSup=$s  tau_w=$tw  limit=${lim}ms  budget=$budget"
    BENCH_CSV=1 BENCH_ALGOS="$algo" java ${JAVA_OPTS:-} -cp target/classes welt.runner.BenchmarkMain \
        "$ds" "$s" "$tw" "$budget" "$lim" "$wu" "$me" > "$tmp" 2>&1 || code=$?
    cat "$tmp"
    if [ "$code" -eq 4 ]; then
        echo "ERROR: exact-mode violation in $algo $ds minSup=$s; stopping." >&2
        exit 4
    fi
    if [ "$(grep -c '^CSV,' "$tmp" || true)" -ge 2 ]; then
        append_csv "$tmp" "$out"
    else
        status=CRASH
        if grep -q 'OutOfMemoryError' "$tmp"; then status=OOM; fi
        echo "!!! $algo $ds minSup=$s: the JVM exited with code $code without a result row -> status=$status" >&2
        append_failure_row "$out" "$(basename "$ds")" "$algo" "$s" "$tw" "$lim" "$budget" "$status"
    fi
    rm -f "$tmp"
    checkpoint "$algo $(basename "$ds") minSup=$s tau_w=$tw: $(last_status "$out")"
}

# The voter BenchmarkMain uses for an algorithm (WELT_VOTER for WeLT and WeLT-noTableBuild,
# degree-only otherwise).
expected_voter() {
    case "$1" in
        WeLT|WeLT-noTableBuild) echo "${WELT_VOTER:-1,1,1}" ;;
        *) echo "0,1,0" ;;
    esac
}

# cell_done <csv> <key=value>...: true when the CSV already holds a row for the cell (resume).
cell_done() {
    local py
    py=$(find_python)
    [ -n "$py" ] || return 1
    $py tools/cell_done.py "$@"
}

# last_status <csv>: status of the last row (for progress messages).
last_status() {
    local py
    py=$(find_python)
    [ -n "$py" ] && [ -s "$1" ] || { echo "?"; return 0; }
    $py -c 'import csv,sys; r=list(csv.DictReader(open(sys.argv[1]))); print(r[-1].get("status","?") if r else "?")' "$1" | tr -d '\r' || echo "?"
}

# report_status <message>: with CHECKPOINT=1, one line in the private status repository (same
# layout as report.ps1: <repo>/<project>/<machine>.log), committed and pushed; otherwise only
# printed. Never stops the caller. RUN_STATUS_DIR (default $HOME/work/run-status),
# STATUS_PROJECT (default 01_SubWeightedGraphMiner).
report_status() {
    local repo="${RUN_STATUS_DIR:-$HOME/work/run-status}" project="${STATUS_PROJECT:-01_SubWeightedGraphMiner}"
    local machine="${COMPUTERNAME:-$(hostname)}" line
    line="$(date +%Y-%m-%dT%H:%M:%S) $1"
    echo "[status] $line"
    [ "${CHECKPOINT:-0}" = "1" ] || return 0
    if [ ! -d "$repo/.git" ]; then
        echo "WARNING: status repository $repo not found; line not recorded" >&2
        return 0
    fi
    mkdir -p "$repo/$project"
    printf '%s\r\n' "$line" >> "$repo/$project/$machine.log"
    git -C "$repo" add -- "$project/$machine.log" >/dev/null 2>&1 || { echo "WARNING: report: git add failed" >&2; return 0; }
    git -C "$repo" commit -q -m "$project $machine: $1" >/dev/null 2>&1 || { echo "WARNING: report: git commit failed" >&2; return 0; }
    git -C "$repo" pull -q --rebase --autostash >/dev/null 2>&1 || echo "WARNING: report: git pull --rebase failed" >&2
    git -C "$repo" push -q >/dev/null 2>&1 || echo "WARNING: report: push failed (committed locally)" >&2
    return 0
}

# checkpoint <message>: with CHECKPOINT=1, commit results/ and push it to the campaign branch
# (CAMPAIGN_BRANCH, default campaign-revision-r2), then report the message. Runs BETWEEN cells
# only, never while a measurement runs. A failed push is reported and retried at the next
# checkpoint; the run continues. Without CHECKPOINT=1 it only prints the message.
checkpoint() {
    if [ "${CHECKPOINT:-0}" != "1" ]; then
        echo "[checkpoint] $1"
        return 0
    fi
    local branch="${CAMPAIGN_BRANCH:-campaign-revision-r2}" pushed="pushed"
    git add -- results >/dev/null 2>&1 || true
    git commit -q -m "Results: $1" >/dev/null 2>&1 || true
    git push -q origin "HEAD:refs/heads/$branch" >/dev/null 2>&1 || pushed="PUSH FAILED (retried at the next checkpoint)"
    report_status "$1 [$pushed $(git rev-parse --short HEAD)]"
}

# run_ablation_cell <out.csv> <dataset.lg> <minSup> <minWeight> <limitMs> <warmup> <measured>
# Runs every ablation variant of one cell (or only the variants named in ABLATION_ONLY), each in its own JVM (ABLATION_VARIANT), and appends
# the rows. An invariant violation (exit 5) is reported and the campaign continues; a JVM that
# dies without a row is written to <out.csv>.failures.
run_ablation_cell() {
    local out="$1" ds="$2" s="$3" tw="$4" lim="$5" wu="$6" me="$7" v tmp code all
    all=$(java -cp target/classes welt.runner.AblationMain --variants | tr -d '\r')
    for v in ${ABLATION_ONLY:-}; do
        case " $(echo $all) " in *" $v "*) ;; *) echo "ERROR: ABLATION_ONLY names an unknown variant: $v" >&2; exit 1 ;; esac
    done
    for v in ${ABLATION_ONLY:-$all}; do
        if cell_done "$out" "dataset=$(basename "$ds")" "variant=$v" "minSup=$s" "minWeight=$tw" \
            || grep -q "^$(basename "$ds"),$v,$s,$tw," "$out.failures" 2>/dev/null; then
            echo "--- skip (already recorded): ablation $v $ds minSup=$s"
            continue
        fi
        tmp=$(mktemp); code=0
        echo ">>> ablation $v  $ds  minSup=$s  tau_w=$tw  limit=${lim}ms"
        ABLATION_CSV=1 ABLATION_VARIANT="$v" java ${JAVA_OPTS:-} -cp target/classes welt.runner.AblationMain \
            "$ds" "$s" "$tw" "$lim" "$wu" "$me" > "$tmp" 2>&1 || code=$?
        cat "$tmp"
        if [ "$code" -eq 5 ]; then
            echo "!!! INVARIANT VIOLATION: ablation variant $v on $ds minSup=$s differs from full" >&2
        fi
        if [ "$(grep -c '^CSV,' "$tmp" || true)" -ge 2 ]; then
            append_csv "$tmp" "$out"
        else
            local status=CRASH
            if grep -q 'OutOfMemoryError' "$tmp"; then status=OOM; fi
            echo "$(basename "$ds"),$v,$s,$tw,$status,exit=$code" >> "$out.failures"
            echo "!!! ablation $v $ds minSup=$s: no row (exit $code) -> $status, see $out.failures" >&2
        fi
        rm -f "$tmp"
        checkpoint "ablation $v $(basename "$ds") minSup=$s: $(last_status "$out")"
    done
}

# run_resum_cell <out.csv> <dataset.lg> <minSup> <minWeight> <limitMs> <warmup> <measured>
# ReSuM's ALL score on one cell (tools/run_resum.py: one JVM per run, same protocol as run_cell);
# its result set goes to $DUMP_PATTERNS. A cell with a row is skipped (resume).
run_resum_cell() {
    local out="$1" ds="$2" s="$3" tw="$4" lim="$5" wu="$6" me="$7" py
    py=$(find_python)
    if [ "${RESUME:-1}" = "1" ] && cell_done "$out" "dataset=$(basename "$ds")" "algorithm=ReSuM-ALL" "minSup=$s" "minWeight=$tw"; then
        echo "--- skip (already in $(basename "$out")): ReSuM-ALL $ds minSup=$s tau_w=$tw"
        return 0
    fi
    "$py" tools/run_resum.py "$out" "$ds" "$s" "$tw" "$lim" "$wu" "$me" "${DUMP_PATTERNS:-patterns}" || {
        echo "ERROR: tools/run_resum.py failed on $ds minSup=$s (no row written)" >&2
        exit 1
    }
    checkpoint "ReSuM-ALL $(basename "$ds") minSup=$s tau_w=$tw: $(last_status "$out")"
}

# run_all_exact_cell <out.csv> <dataset.lg> <minSup> <minWeight> <limitMs> <warmup> <measured> <patternsDir>
# The ALL score of ReSuM computed exactly: GraMi on the graph of the edges weighing at least
# minWeight (tools/heavy_subgraph.py writes it to datasets/derived/<name>_heavy_<minWeight>.lg,
# outside the clock), one run_cell with the same protocol as every algorithm.
run_all_exact_cell() {
    local out="$1" ds="$2" s="$3" tw="$4" lim="$5" wu="$6" me="$7" pdir="$8" py name heavy
    py=$(find_python)
    name=$(basename "$ds" .lg)
    heavy="datasets/derived/${name}_heavy_${tw}.lg"
    "$py" tools/heavy_subgraph.py "$ds" "$tw" "$heavy" || { echo "ERROR: heavy_subgraph.py failed on $ds tau_w=$tw" >&2; exit 1; }
    DUMP_PATTERNS="$pdir" run_cell "$out" "$heavy" "$s" "$tw" GraMi "$lim" "$wu" "$me"
}

# append_failure_row <out.csv> <dataset> <algorithm> <minSup> <minWeight> <limitMs> <budget> <status>
# Writes a row with the runner's own header (BenchmarkMain --header): identifying columns
# and status filled, partial=1, every measurement empty.
append_failure_row() {
    local out="$1" hdrtmp
    hdrtmp=$(mktemp)
    java -cp target/classes welt.runner.BenchmarkMain --header | tr -d '\r' > "$hdrtmp"
    if [ ! -s "$out" ]; then sed 's/^CSV,//' "$hdrtmp" > "$out"; fi
    sed 's/^CSV,//' "$hdrtmp" | awk -F, -v ds="$2" -v algo="$3" -v s="$4" -v tw="$5" -v lim="$6" \
        -v budget="$7" -v status="$8" -v voter="$(expected_voter "$3" | tr ',' ';')" '
        { for (i = 1; i <= NF; i++) {
              v = ""
              if ($i == "dataset") v = ds;   else if ($i == "algorithm") v = algo
              else if ($i == "minSup") v = s; else if ($i == "minWeight") v = tw
              else if ($i == "limitMs") v = lim; else if ($i == "budget") v = budget
              else if ($i == "mode") v = (budget == 0 ? "exact" : "approx")
              else if ($i == "status") v = status; else if ($i == "partial") v = 1
              else if ($i == "voter") v = voter
              printf "%s%s", v, (i < NF ? "," : "\n")
          } }' >> "$out"
    if [ "$(head -1 "$out")" != "$(sed 's/^CSV,//' "$hdrtmp")" ]; then
        echo "ERROR: header of $out differs from the runner header" >&2
        exit 1
    fi
    rm -f "$hdrtmp"
}

# keep_awake: prevent idle sleep for the lifetime of the calling script.
#   macOS: caffeinate; Windows (Git Bash): tools/keep_awake.ps1 via Windows PowerShell;
#   elsewhere: a warning (disable sleep manually).
keep_awake() {
    if command -v caffeinate >/dev/null 2>&1; then
        caffeinate -i -w $$ &
        echo "[keep_awake] caffeinate holds an idle-sleep assertion for PID $$"
    elif [ -r "/proc/$$/winpid" ] && command -v powershell.exe >/dev/null 2>&1; then
        local winpid
        winpid=$(cat "/proc/$$/winpid")
        powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$PWD/tools/keep_awake.ps1")" \
            -ParentPid "$winpid" &
        echo "[keep_awake] Windows: SetThreadExecutionState held while Windows PID $winpid runs"
    else
        echo "WARNING: no keep-awake mechanism found; disable sleep manually for the whole run." >&2
    fi
}

# require_clean_tree: refuse to run when tracked or untracked files (outside results/)
# differ from HEAD, because the commit hash recorded in meta.json would then describe
# code that did not run. Set ALLOW_DIRTY=1 only for trial runs that will not be reported.
require_clean_tree() {
    local dirty
    dirty=$(git status --porcelain -- . ':(exclude)results' 2>/dev/null || echo "git-unavailable")
    if [ -n "$dirty" ]; then
        if [ "${ALLOW_DIRTY:-0}" = "1" ]; then
            echo "WARNING: working tree differs from HEAD (ALLOW_DIRTY=1); results are not reportable:" >&2
            echo "$dirty" >&2
        else
            echo "ERROR: working tree differs from HEAD; commit, push and pull first:" >&2
            echo "$dirty" >&2
            exit 1
        fi
    fi
}

# print_checklist: what the scripts cannot enforce on the measurement machine.
print_checklist() {
    cat <<'TXT'
Before a measurement run (not enforced by the scripts):
  - Windows Update paused for the whole campaign (a restart loses the batch silently).
  - Power plan "High performance", laptop plugged in, lid-close action "Do nothing".
  - The repository folder excluded from Defender real-time scanning.
  - No other heavy process running; Google Drive / OneDrive sync paused.
TXT
}

# find_python: prints a working Python 3 command (python3, python or py -3), or nothing.
find_python() {
    local c
    for c in python3 python "py -3"; do
        if $c -c 'import sys; sys.exit(0 if sys.version_info[0] == 3 else 1)' >/dev/null 2>&1; then
            echo "$c"
            return 0
        fi
    done
}
