#!/usr/bin/env python3
"""Move the rows of interrupted cells out of a result CSV so that the campaign runs them again
(standard library only).

Usage:  python tools/retry_cells.py <csv> [--status S] <key=value> ...

A row is moved when every key equals the given value (numbers compared numerically, as in
tools/cell_done.py) and its status is S (default CRASH). The moved rows are appended to
<csv>.retried.csv with the time of the move, so the record stays auditable; the other rows keep
their order and their bytes. A cell left without a row is run again by the next campaign step (resume).

Exit 0 when at least one row was moved; 1 when no row matches the keys, or rows match but none
has status S (both cases are reported separately, and nothing is changed).
"""
import csv
import datetime
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cell_done import same  # noqa: E402


def main():
    args = sys.argv[1:]
    status = "CRASH"
    if len(args) >= 2 and args[0] != "--status" and "--status" in args:
        i = args.index("--status")
        status = args[i + 1]
        del args[i:i + 2]
    elif len(args) >= 2 and args[0] == "--status":
        status = args[1]
        args = args[2:]
    if len(args) < 2 or any("=" not in a for a in args[1:]):
        sys.exit(__doc__)
    path, want = args[0], dict(a.split("=", 1) for a in args[1:])
    with open(path, "rb") as fh:
        raw = fh.read().split(b"\n")
    if raw and raw[-1] == b"":
        raw = raw[:-1]
    text = [line.decode("utf-8").rstrip("\r") for line in raw]
    header = next(csv.reader([text[0]]))
    missing = [k for k in want if k not in header]
    if missing:
        print(f"retry_cells: {path} has no column {missing}", file=sys.stderr)
        sys.exit(1)
    matching, moved = [], []
    for i, line in enumerate(text[1:], start=1):
        values = next(csv.reader([line]))
        if len(values) != len(header):
            print(f"retry_cells: {path}:{i + 1} has {len(values)} fields, header {len(header)}; nothing changed", file=sys.stderr)
            sys.exit(1)
        row = dict(zip(header, values))
        if all(same(row[k], v, k) for k, v in want.items()):
            matching.append(i)
            if row.get("status") == status:
                moved.append(i)
    if not matching:
        print(f"retry_cells: no row of {path} matches {want}; nothing changed", file=sys.stderr)
        sys.exit(1)
    if not moved:
        found = sorted({dict(zip(header, next(csv.reader([text[i]])))).get("status") for i in matching})
        print(f"retry_cells: {len(matching)} row(s) match {want} but none has status {status} "
              f"(found {found}); nothing changed", file=sys.stderr)
        sys.exit(1)
    audit = path + ".retried.csv"
    new_audit = not os.path.exists(audit) or os.path.getsize(audit) == 0
    stamp = datetime.datetime.now().astimezone().isoformat(timespec="seconds")
    with open(audit, "ab") as fh:
        if new_audit:
            fh.write(raw[0].rstrip(b"\r") + b",retriedAt\n")
        for i in moved:
            fh.write(raw[i].rstrip(b"\r") + b"," + stamp.encode() + b"\n")
    gone = set(moved)
    tmp = path + ".tmp"
    with open(tmp, "wb") as fh:
        for i, line in enumerate(raw):
            if i not in gone:
                fh.write(line + b"\n")
    os.replace(tmp, path)
    print(f"retry_cells: moved {len(moved)} row(s) with status {status} from {path} to {audit}; "
          f"{len(raw) - 1 - len(moved)} row(s) kept")


if __name__ == "__main__":
    main()
