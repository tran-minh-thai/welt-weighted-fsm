#!/usr/bin/env bash
# Helpers shared by the experiment scripts. Source it: . "$(dirname "$0")/tools/common.sh"

# append_csv <runner-output-file> <csv-file>
# Copies the "CSV," lines printed by a runner into <csv-file>. The header is taken from the
# runner's own output (never typed in a script). A new file gets the header; an existing
# file must carry the identical header, otherwise the script stops instead of mixing
# column layouts.
append_csv() {
    local tmp="$1" out="$2" hdr
    hdr=$(grep -m1 '^CSV,dataset,' "$tmp" | sed 's/^CSV,//' || true)
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
    grep '^CSV,' "$tmp" | grep -v '^CSV,dataset,' | sed 's/^CSV,//' >> "$out" || true
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
    local tmp code=0 status
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
}

# append_failure_row <out.csv> <dataset> <algorithm> <minSup> <minWeight> <limitMs> <budget> <status>
# Writes a row with the runner's own header (BenchmarkMain --header): identifying columns
# and status filled, partial=1, every measurement empty.
append_failure_row() {
    local out="$1" hdrtmp
    hdrtmp=$(mktemp)
    java -cp target/classes welt.runner.BenchmarkMain --header > "$hdrtmp"
    if [ ! -s "$out" ]; then sed 's/^CSV,//' "$hdrtmp" > "$out"; fi
    sed 's/^CSV,//' "$hdrtmp" | awk -F, -v ds="$2" -v algo="$3" -v s="$4" -v tw="$5" -v lim="$6" \
        -v budget="$7" -v status="$8" '
        { for (i = 1; i <= NF; i++) {
              v = ""
              if ($i == "dataset") v = ds;   else if ($i == "algorithm") v = algo
              else if ($i == "minSup") v = s; else if ($i == "minWeight") v = tw
              else if ($i == "limitMs") v = lim; else if ($i == "budget") v = budget
              else if ($i == "mode") v = (budget == 0 ? "exact" : "approx")
              else if ($i == "status") v = status; else if ($i == "partial") v = 1
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
