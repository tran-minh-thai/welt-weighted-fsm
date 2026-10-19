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
