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
