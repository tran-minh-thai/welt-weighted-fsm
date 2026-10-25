#!/usr/bin/env python3
"""Summarise the voting-analysis runs into number keys and a table (standard library only).

    python3 tools/summarise_voting.py <out dir> [--floor 1.10]

Reads <out dir>/runs.jsonl written by tools/voting_probe.py and writes <out dir>/numbers.json and
<out dir>/summary.md. The work voting controls is the backtracking of the MNI counts and of the
weight checks of the mining phase (the lookup-table build and the reused results are outside it).
A ratio is (work of the compared order) / (work of the default voter 1,1,1): above 1 the default
does less work. Keys, with <c> the cell name with '.' replaced by '_':

  e5m.<c>.share_bt.{mni,weight,table}       share of all backtracking nodes, default voter
  e5m.<c>.share_search.{mni,weight,table}   share of all embedding searches, default voter
  e5m.<c>.{mni,weight}.calls                calls with searches in the mining phase
  e5m.<c>.{mni,weight}.orders_invariant_share   calls where the 22 voters give one order (start)
  e5m.<c>.{mni,weight}.distinct_orders.max
  e5m.<c>.{mni,weight}.signal_const_share.{dom,deg,w}
  e5m.<c>.mni_searches.{default,min,max}    MNI embedding searches of the mining phase over the grid
  e5m.<c>.pushdown_share                    values found valid by push-down / values examined
  e5m.<c>.weight.{pass,fail}.{calls,bt}     weight checks of the mining phase, default voter
  e5m.<c>.weight.{pass,fail}.bt_ratio       largest / smallest backtracking of that part over the grid
  e5m.<c>.pattern_vertices.{mean,max}       vertices of the patterns of the mining MNI counts
  e5m.<c>.controlled_bt.{default,best,worst}
  e5x.<c>.<mode>.bt_ratio                   index, reverse, random_<seed>, random.{min,mean,max}
  e5x.<c>.grid.bt_ratio.{best,worst}        best and worst voter of the grid
  e5x.<c>.total_bt_ratio.<mode>             same ratios on all backtracking nodes (secondary)
  e5x.count.{above_floor,below_floor,mode_better}   over every (cell, compared order)
Exits 1 when a cell lacks the default run or a mode, or when runs of a cell disagree on the result set.
"""
import json
import statistics
import sys
from pathlib import Path

GRID_N = 22
SEEDS = [1, 2, 3, 4, 5]


def controlled(r):
    s = r["stats"]
    return s["mining.mni.backtrack"] + s["mining.weight.backtrack"]


def share(a, b):
    return a / b if b else 0.0


def main():
    out = Path(sys.argv[1])
    floor = float(sys.argv[sys.argv.index("--floor") + 1]) if "--floor" in sys.argv else 1.10
    runs = [json.loads(l) for l in (out / "runs.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
    cells = {}
    for r in runs:
        cells.setdefault(r["cell"], {})[r["voter_or_mode"]] = r
    keys, rows, problems = {}, [], []
    above = below = better = 0
    for cell, rs in cells.items():
        c = cell.replace(".", "_")
        grid = {k: v for k, v in rs.items() if v["mode"] == "vote"}
        modes = ["index", "reverse"] + [f"random:{s}" for s in SEEDS]
        if "1,1,1" not in rs or len(grid) != GRID_N or any(m not in rs for m in modes):
            problems.append(f"{cell}: {len(grid)} grid runs, modes present {sorted(set(rs) & set(modes))}")
            continue
        if len({v["result_sha256"] for v in rs.values()}) != 1:
            problems.append(f"{cell}: runs disagree on the result set")
        d = rs["1,1,1"]
        s = d["stats"]
        k = f"e5m.{c}"
        tot_bt = d["backtrack"]
        tot_s = d["mniIsoCalls"] + d["weightIsoCalls"]
        keys[f"{k}.share_bt.mni"] = share(s["mining.mni.backtrack"], tot_bt)
        keys[f"{k}.share_bt.weight"] = share(s["mining.weight.backtrack"], tot_bt)
        keys[f"{k}.share_bt.table"] = share(s["table.mni.backtrack"] + s["table.weight.backtrack"], tot_bt)
        keys[f"{k}.share_search.mni"] = share(s["mining.mni.searches"], tot_s)
        keys[f"{k}.share_search.weight"] = share(s["mining.weight.searches"], tot_s)
        keys[f"{k}.share_search.table"] = share(s["table.mni.searches"] + s["table.weight.searches"], tot_s)
        for kind in ("mni", "weight"):
            n = s[f"mining.{kind}.calls"]
            keys[f"{k}.{kind}.calls"] = n
            keys[f"{k}.{kind}.orders_invariant_share"] = share(s[f"mining.{kind}.invariant_calls"], n)
            keys[f"{k}.{kind}.distinct_orders.max"] = s[f"mining.{kind}.distinct_max"]
            for sig in ("dom", "deg", "w"):
                keys[f"{k}.{kind}.signal_const_share.{sig}"] = share(s[f"mining.{kind}.const_{sig}"], n)
        ms = [v["stats"]["mining.mni.searches"] for v in grid.values()]
        keys[f"{k}.mni_searches.default"] = s["mining.mni.searches"]
        keys[f"{k}.mni_searches.min"] = min(ms)
        keys[f"{k}.mni_searches.max"] = max(ms)
        keys[f"{k}.pushdown_share"] = share(s["mining.mni.pushdown_values"],
                                            s["mining.mni.pushdown_values"] + s["mining.mni.searched_values"])
        for part in ("pass", "fail"):
            keys[f"{k}.weight.{part}.calls"] = s[f"mining.weight.{part}_calls"]
            keys[f"{k}.weight.{part}.bt"] = s[f"mining.weight.{part}_backtrack"]
            vals = [v["stats"][f"mining.weight.{part}_backtrack"] for v in grid.values()]
            keys[f"{k}.weight.{part}.bt_ratio"] = (max(vals) / min(vals)) if min(vals) else (1.0 if max(vals) == 0 else None)
        n = s["mining.mni.calls"]
        keys[f"{k}.pattern_vertices.mean"] = share(s["mining.mni.vertices_sum"], n)
        keys[f"{k}.pattern_vertices.max"] = s["mining.mni.vertices_max"]
        base = controlled(d)
        cg = {v: controlled(r) for v, r in grid.items()}
        best_v = min(cg, key=cg.get)
        worst_v = max(cg, key=cg.get)
        keys[f"{k}.controlled_bt.default"] = base
        keys[f"{k}.controlled_bt.best"] = cg[best_v]
        keys[f"{k}.controlled_bt.worst"] = cg[worst_v]
        keys[f"{k}.controlled_bt.best_voter"] = best_v
        keys[f"{k}.controlled_bt.worst_voter"] = worst_v
        x = f"e5x.{c}"
        ratio = {}
        for m in modes:
            ratio[m.replace(":", "_")] = share(controlled(rs[m]), base) if base else None
        rnd = [ratio[f"random_{s_}"] for s_ in SEEDS]
        ratio["grid_best"] = share(cg[best_v], base) if base else None
        ratio["grid_worst"] = share(cg[worst_v], base) if base else None
        for m, v in ratio.items():
            if m.startswith("grid_"):
                keys[f"{x}.grid.bt_ratio.{m[5:]}"] = v
            else:
                keys[f"{x}.{m}.bt_ratio"] = v
        if None not in rnd:
            keys[f"{x}.random.bt_ratio.min"] = min(rnd)
            keys[f"{x}.random.bt_ratio.mean"] = statistics.mean(rnd)
            keys[f"{x}.random.bt_ratio.max"] = max(rnd)
        for m in modes + ["grid_worst"]:
            r_ = rs[m] if m in rs else rs[worst_v]
            keys[f"{x}.total_bt_ratio.{m.replace(':', '_')}"] = share(r_["backtrack"], d["backtrack"])
        for m in modes + ["grid_best", "grid_worst"]:
            v = ratio[m.replace(":", "_")]
            if v is None:
                continue
            if v > floor:
                above += 1
            elif v < 1 / floor:
                better += 1
            else:
                below += 1
        bysize = {}
        for v in (best_v, worst_v):
            for kind in ("mni", "weight"):
                for h, (calls, bt, _srch) in grid[v]["stats"][f"mining.{kind}.by_size"].items():
                    bysize.setdefault(int(h), {}).setdefault(v, 0)
                    bysize[int(h)][v] += bt
        diff = cg[worst_v] - cg[best_v]
        for h, by in sorted(bysize.items()):
            keys[f"{k}.size_{h}.share_of_gap"] = share(by.get(worst_v, 0) - by.get(best_v, 0), diff) if diff else 0.0
        rows.append((cell, d["group"], d["N"], d["M"], base, s["mining.mni.calls"], s["mining.weight.calls"],
                     keys[f"{k}.share_bt.mni"] + keys[f"{k}.share_bt.weight"], ratio))
    keys["e5x.count.above_floor"] = above
    keys["e5x.count.below_floor"] = below
    keys["e5x.count.mode_better"] = better
    keys["e5x.floor"] = floor
    keys["e5x.cells"] = len(rows)
    (out / "numbers.json").write_text(json.dumps(keys, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    lines = [f"Voting analysis: {len(rows)} cells; ratio = controlled backtracking of the order / of voter 1,1,1;"
             f" floor {floor}", "",
             "| cell | group | N | M | controlled bt | mni calls | weight calls | controlled share | index | reverse |"
             " random min-max | grid best | grid worst |",
             "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|---:|"]
    f = lambda v: "–" if v is None else f"{v:.3f}"
    for cell, grp, N, M, base, mc, wc, sh, ratio in rows:
        rnd = [ratio[f"random_{s_}"] for s_ in SEEDS]
        rr = "–" if None in rnd else f"{min(rnd):.3f}–{max(rnd):.3f}"
        lines.append(f"| {cell} | {grp} | {N} | {M} | {base} | {mc} | {wc} | {sh:.3f} | {f(ratio['index'])} | "
                     f"{f(ratio['reverse'])} | {rr} | {f(ratio['grid_best'])} | {f(ratio['grid_worst'])} |")
    lines += ["", f"compared orders above the floor: {above}; within the floor: {below}; better than the default "
                  f"beyond the floor: {better}"]
    if problems:
        lines += ["", "PROBLEMS:"] + problems
    (out / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    sys.exit(1 if problems or not rows else 0)


if __name__ == "__main__":
    main()
