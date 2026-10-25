#!/usr/bin/env python3
"""Run times of the order modes (revision.sh b2modes) under the rule fixed before they were measured.

    python3 tools/summarise_order_times.py <B2_order_modes.csv> <voting numbers.json> <out dir> [<campaign dir>]

For each cell, every compared order (order mode, or best and worst voter of the grid) is set against
the voter 1,1,1 of the same cell and the same run. The verdict is the one of the efficiency experiment
(tools/summarise_campaign.py: tie when |t_a - t_b| < 3 sqrt(sd_a^2 + sd_b^2) over the measured runs)
with the floor of the ablation (MIN_EFFECT): a difference counts only when the tie rule is passed and
the ratio lies beyond 1/MIN_EFFECT .. MIN_EFFECT. "default faster" means voting reduces the time.
The backtracking ratio of the same order (voting numbers.json) is reported next to it.

With <campaign dir>, the phase times of the full WeLT in that campaign's ablation files (B4_ablation*.csv,
B4_low.csv, phase timing on) give, per cell, the share of the run time spent in MNI counting and weight
checks (e5t.<c>.phase_share.search), in canonical codes and filters (.canon_filter) and in the table
build (.table): the part of the time the order can act on is at most the search share.

Writes <out dir>/times.json (keys e5t.*) and <out dir>/times.md. Exits 1 when a cell lacks the default
row, a row is not OK, or the counters of a row differ between two rows of one order (none expected).
"""
import csv
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from summarise_campaign import MIN_EFFECT, ms, verdict  # noqa: E402

CELL = {("citeseer.lg", 300, 90.0): "citeseer_300_w90", ("citeseer.lg", 300, 96.0): "citeseer_300_w96"}


def cell_name(ds, s, tw):
    return CELL.get((ds, s, tw), f"{ds[:-3]}_{s}")


def label(v):
    if v.startswith("order:"):
        return v[len("order:"):].replace(":", "_")
    return None


def main():
    rows = list(csv.DictReader(open(sys.argv[1], encoding="utf-8")))
    vn = json.load(open(sys.argv[2], encoding="utf-8"))
    out = Path(sys.argv[3])
    out.mkdir(parents=True, exist_ok=True)
    cells, problems = {}, []
    for r in rows:
        if r["status"] != "OK":
            problems.append(f"{r['dataset']} {r['minSup']} {r['voter']}: status {r['status']}")
            continue
        cells.setdefault(cell_name(r["dataset"], int(r["minSup"]), float(r["minWeight"])), {})[r["voter"]] = r
    keys, lines = {}, ["| cell | default ms | compared order | ms | time ratio | verdict | backtracking ratio |",
                       "|---|---:|---|---:|---:|---|---:|"]
    count = {"default_faster": 0, "tie": 0, "default_slower": 0}
    for c, rs in sorted(cells.items()):
        d = rs.get("1;1;1")
        if d is None:
            problems.append(f"{c}: no row of the voter 1,1,1")
            continue
        keys[f"e5t.{c}.default.ms"] = ms(d)
        keys[f"e5t.{c}.default.sd_ms"] = float(d["totalSdMs"])
        best = vn.get(f"e5m.{c}.controlled_bt.best_voter")
        worst = vn.get(f"e5m.{c}.controlled_bt.worst_voter")
        for v, r in sorted(rs.items()):
            if v == "1;1;1":
                continue
            name = label(v)
            if name is None:
                vv = v.replace(";", ",")
                name = "grid_best" if best and _same(vv, best) else "grid_worst" if worst and _same(vv, worst) else None
                if name is None:
                    problems.append(f"{c}: voter {v} is neither the best nor the worst of the grid")
                    continue
            ratio = ms(r) / ms(d)
            v_tie = verdict(d, r)                       # 'faster': the default is faster
            if v_tie == "tie" or 1 / MIN_EFFECT <= ratio <= MIN_EFFECT:
                ver = "tie"
            else:
                ver = "default_faster" if v_tie == "faster" else "default_slower"
            count[ver] += 1
            if name.startswith("grid_"):
                bt = vn.get(f"e5x.{c}.grid.bt_ratio.{name[5:]}")
            else:
                bt = vn.get(f"e5x.{c}.{name}.bt_ratio")
            keys[f"e5t.{c}.{name}.ms"] = ms(r)
            keys[f"e5t.{c}.{name}.time_ratio"] = ratio
            keys[f"e5t.{c}.{name}.verdict"] = ver
            lines.append(f"| {c} | {ms(d):.1f} | {name} | {ms(r):.1f} | {ratio:.3f} | {ver} | "
                         f"{'–' if bt is None else f'{bt:.3f}'} |")
    if len(sys.argv) > 4:
        camp = Path(sys.argv[4])
        lines += ["", "| cell | phase share: search | canonical codes and filters | table build |", "|---|---:|---:|---:|"]
        for fn in ("B4_ablation.csv", "B4_ablation_hard.csv", "B4_low.csv"):
            for r in csv.DictReader(open(camp / fn, encoding="utf-8")):
                if r.get("variant") != "full" or r["status"] != "OK" or not r.get("mniMs"):
                    continue
                c = cell_name(r["dataset"], int(r["minSup"]), float(r["minWeight"]))
                t = float(r["totalMs"])
                sh = {"search": (float(r["mniMs"]) + float(r["weightMs"])) / t,
                      "canon_filter": (float(r["canonMs"]) + float(r["filterMs"])) / t,
                      "table": float(r["tableBuildMs"]) / t}
                for k, v in sh.items():
                    keys[f"e5t.{c}.phase_share.{k}"] = v
                lines.append(f"| {c} | {sh['search']:.2f} | {sh['canon_filter']:.2f} | {sh['table']:.2f} |")
    for k, v in count.items():
        keys[f"e5t.count.{k}"] = v
    keys["e5t.cells"] = len(cells)
    keys["e5t.floor"] = MIN_EFFECT
    (out / "times.json").write_text(json.dumps(keys, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    lines += ["", f"verdicts: {count}"] + (["", "PROBLEMS:"] + problems if problems else [])
    (out / "times.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    sys.exit(1 if problems else 0)


def _same(a, b):
    try:
        return [float(x) for x in a.split(",")] == [float(x) for x in b.split(",")]
    except ValueError:
        return a == b


if __name__ == "__main__":
    main()
