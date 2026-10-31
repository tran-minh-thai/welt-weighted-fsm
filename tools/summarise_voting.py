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
  e5x.<c>.<mode>.bt_ratio_{mni,weight}     the same ratio on the MNI part and on the weight-check part
  e5m.<c>.controlled_bt.grid_distinct       number of distinct values over the 22 voters
  e5m.<c>.no_controlled_work                1 when the mining phase has no MNI count with searches and at
                                            most one weight check (the ratios then compare a few nodes)
  e5x.count.{above_floor,below_floor,mode_better,comparisons}   over every (cell, compared order), cells
                                            with controlled work only; e5x.count.novote.* the same over the
                                            orders without a vote (index, reverse, random) only
  e5m.<c>.weight.fail_share                 failing weight checks / all weight checks, backtracking nodes
  e5x.<c>.<mode>.mni_searches_ratio, e5x.<c>.{default,<mode>}.nodes_per_search   mining MNI counts
  e5x.<c>.<mode>.table_bt_ratio             backtracking of the lookup-table build (also ordered by the vote)
  e5x.table_bt_ratio.<mode>.{min,max}       over the cells with a table build
  e5x.count.reverse_weight_above, e5x.reverse.bt_ratio_weight.max   weight-check part under reverse
  e5x.cells_with_work, e5x.cells_without_work, e5x.random.seeds
  e5x.count.reverse_above_cells             cells with work outside LastFM Asia where reverse needs more nodes
                                            than the default beyond the floor; e5x.count.non_lastfm_cells their number
  e5x.<c>.<mode>.weight_share_of_gap        (weight-check nodes of the order - of the default) / (controlled
                                            nodes of the order - of the default): the part of the difference
                                            that comes from the start of the weight check
  e5x.<c>.{default,<mode>}.nodes.{mni,weight,table}   backtracking nodes of the MNI counts and of the
                                            weight checks of the mining phase, and of the lookup-table build
  e5x.<c>.single.<a_b_c>.ratio             the same ratio for the single-criterion voters 1,0,0 (domain size),
                                            0,1,0 (degree) and 0,0,1 (weight); .nodes.{mni,weight} their parts
  e5x.<c>.grid_best.{triple,tied}           the voters of the grid with the fewest controlled nodes (ties listed)
  e5x.<ds>.grid_best_common.{count,triple}  voters best in every cell with controlled work of a dataset
  e5x.<ds>.grid_minimax.{triple,cells,ratio.<cell>}   the voter whose largest ratio over those cells is smallest
  e5x.grid_never_worse.{cells,count,triples}, e5x.grid_never_worse.<voter>.below   voters never above the
                                            default in any cell with controlled work, and in how many below
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
    nv = {"above": 0, "below": 0, "better": 0}
    rev_w_above, rev_w = 0, []
    rev_above_cells = non_lastfm = 0
    table_r = {}
    gridwork = {}
    cost_count = {"above": 0, "below": 0, "better": 0}
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
        wb = s["mining.weight.pass_backtrack"] + s["mining.weight.fail_backtrack"]
        keys[f"{k}.weight.fail_share"] = share(s["mining.weight.fail_backtrack"], wb)
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
        keys[f"{k}.controlled_bt.grid_distinct"] = len(set(cg.values()))
        idle = s["mining.mni.calls"] == 0 and s["mining.weight.calls"] <= 1
        keys[f"{k}.no_controlled_work"] = int(idle)
        tied = sorted(v for v in cg if cg[v] == cg[best_v])
        keys[f"e5x.{c}.grid_best.triple"] = "; ".join(tied)
        keys[f"e5x.{c}.grid_best.tied"] = len(tied)
        if not idle:
            gridwork[cell] = (cg, base)
        for mk, run in [("default", d)] + [(m.replace(":", "_"), rs[m]) for m in modes + (["cost"] if "cost" in rs else [])]:
            st = run["stats"]
            keys[f"e5x.{c}.{mk}.nodes.mni"] = st["mining.mni.backtrack"]
            keys[f"e5x.{c}.{mk}.nodes.weight"] = st["mining.weight.backtrack"]
            keys[f"e5x.{c}.{mk}.nodes.table"] = st["table.mni.backtrack"] + st["table.weight.backtrack"]
        for t, tk in (("1.0,0.0,0.0", "1_0_0"), ("0.0,1.0,0.0", "0_1_0"), ("0.0,0.0,1.0", "0_0_1")):
            if t not in grid:
                problems.append(f"{cell}: no run of the single-criterion voter {t}")
                continue
            st = grid[t]["stats"]
            keys[f"e5x.{c}.single.{tk}.ratio"] = share(controlled(grid[t]), base) if base else None
            keys[f"e5x.{c}.single.{tk}.nodes.mni"] = st["mining.mni.backtrack"]
            keys[f"e5x.{c}.single.{tk}.nodes.weight"] = st["mining.weight.backtrack"]
        x = f"e5x.{c}"
        ratio = {}
        keys[f"e5x.{c}.default.nodes_per_search"] = share(s["mining.mni.backtrack"], s["mining.mni.searches"])
        tb0 = s["table.mni.backtrack"] + s["table.weight.backtrack"]
        for m in modes + (["cost"] if "cost" in rs else []):
            sm = rs[m]["stats"]
            mk = m.replace(":", "_")
            keys[f"e5x.{c}.{mk}.mni_searches_ratio"] = share(sm["mining.mni.searches"], s["mining.mni.searches"]) if s["mining.mni.searches"] else None
            keys[f"e5x.{c}.{mk}.nodes_per_search"] = share(sm["mining.mni.backtrack"], sm["mining.mni.searches"])
            gap = (sm["mining.mni.backtrack"] + sm["mining.weight.backtrack"]) - (s["mining.mni.backtrack"] + s["mining.weight.backtrack"])
            if gap and not (s["mining.mni.calls"] == 0 and s["mining.weight.calls"] <= 1):
                keys[f"e5x.{c}.{mk}.weight_share_of_gap"] = (sm["mining.weight.backtrack"] - s["mining.weight.backtrack"]) / gap
            if tb0:
                v = share(sm["table.mni.backtrack"] + sm["table.weight.backtrack"], tb0)
                keys[f"e5x.{c}.{mk}.table_bt_ratio"] = v
                table_r.setdefault(mk, []).append(v)
        if "cost" in rs:
            ratio["cost"] = share(controlled(rs["cost"]), base) if base else None
            for part in ("mni", "weight"):
                b0 = s[f"mining.{part}.backtrack"]
                keys[f"{x}.cost.bt_ratio_{part}"] = share(rs["cost"]["stats"][f"mining.{part}.backtrack"], b0) if b0 else None
            keys[f"{x}.total_bt_ratio.cost"] = share(rs["cost"]["backtrack"], d["backtrack"])
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
        for m in modes:
            for part in ("mni", "weight"):
                b0 = s[f"mining.{part}.backtrack"]
                keys[f"{x}.{m.replace(':', '_')}.bt_ratio_{part}"] = share(rs[m]["stats"][f"mining.{part}.backtrack"], b0) if b0 else None
        for m in modes + ["grid_worst"]:
            r_ = rs[m] if m in rs else rs[worst_v]
            keys[f"{x}.total_bt_ratio.{m.replace(':', '_')}"] = share(r_["backtrack"], d["backtrack"])
        if not idle and not cell.startswith("lastfm."):
            non_lastfm += 1
            rev_above_cells += ratio["reverse"] is not None and ratio["reverse"] > floor
        if not idle:
            for m in modes:
                v = ratio[m.replace(":", "_")]
                if v is not None:
                    nv["above" if v > floor else "better" if v < 1 / floor else "below"] += 1
            vw = keys.get(f"{x}.reverse.bt_ratio_weight")
            if vw is not None:
                rev_w.append(vw)
                rev_w_above += vw > floor
        for m in modes + ["grid_best", "grid_worst"]:
            v = ratio[m.replace(":", "_")]
            if v is None or idle:
                continue
            if v > floor:
                above += 1
            elif v < 1 / floor:
                better += 1
            else:
                below += 1
        if "cost" in ratio and ratio["cost"] is not None and not idle:
            v = ratio["cost"]
            cost_count["above" if v > floor else "better" if v < 1 / floor else "below"] += 1
        bysize = {}
        for v in (best_v, worst_v):
            for kind in ("mni", "weight"):
                for h, (calls, bt, _srch) in grid[v]["stats"][f"mining.{kind}.by_size"].items():
                    bysize.setdefault(int(h), {}).setdefault(v, 0)
                    bysize[int(h)][v] += bt
        diff = cg[worst_v] - cg[best_v]
        for h, by in sorted(bysize.items()):
            keys[f"{k}.size_{h}.share_of_gap"] = share(by.get(worst_v, 0) - by.get(best_v, 0), diff) if diff else 0.0
        rows.append((cell + (" (no controlled work)" if idle else ""), d["group"], d["N"], d["M"], base, s["mining.mni.calls"], s["mining.weight.calls"],
                     keys[f"{k}.share_bt.mni"] + keys[f"{k}.share_bt.weight"], ratio))
    keys["e5x.count.comparisons"] = above + below + better
    for kk, vv in nv.items():
        keys[f"e5x.count.novote.{kk}"] = vv
    keys["e5x.count.novote.comparisons"] = sum(nv.values())
    keys["e5x.count.reverse_weight_above"] = rev_w_above
    keys["e5x.count.reverse_above_cells"] = rev_above_cells
    keys["e5x.count.non_lastfm_cells"] = non_lastfm
    keys["e5x.reverse.bt_ratio_weight.max"] = max(rev_w) if rev_w else None
    for mk, vals in table_r.items():
        keys[f"e5x.table_bt_ratio.{mk}.min"] = min(vals)
        keys[f"e5x.table_bt_ratio.{mk}.max"] = max(vals)
    # grid triples across the cells with controlled work: best in every cell of a dataset, the triple with the
    # smallest largest ratio, and the triples whose node count never exceeds the default in any cell
    by_ds = {}
    for cell in gridwork:
        by_ds.setdefault(cell.split(".")[0], []).append(cell)
    for dname, cs in sorted(by_ds.items()):
        triples = sorted(set.intersection(*[set(gridwork[x][0]) for x in cs]) - {"1,1,1"})
        best_all = [t for t in triples if all(gridwork[x][0][t] == min(v for k2, v in gridwork[x][0].items() if k2 != "1,1,1") for x in cs)]
        keys[f"e5x.{dname}.grid_best_common.count"] = len(best_all)
        keys[f"e5x.{dname}.grid_best_common.triple"] = "; ".join(best_all)
        mm = min(triples, key=lambda t: (max(share(gridwork[x][0][t], gridwork[x][1]) for x in cs), t))
        keys[f"e5x.{dname}.grid_minimax.triple"] = mm
        keys[f"e5x.{dname}.grid_minimax.cells"] = len(cs)
        for x in cs:
            keys[f"e5x.{dname}.grid_minimax.ratio.{x.split('.', 1)[1].replace('.', '_')}"] = share(gridwork[x][0][mm], gridwork[x][1])
    allt = sorted(set.intersection(*[set(cg) for cg, _b in gridwork.values()]) - {"1,1,1"}) if gridwork else []
    never = [t for t in allt if all(cg[t] <= b for cg, b in gridwork.values())]
    keys["e5x.grid_never_worse.cells"] = len(gridwork)
    keys["e5x.grid_never_worse.count"] = len(never)
    keys["e5x.grid_never_worse.triples"] = "; ".join(never)
    for t in never:
        keys[f"e5x.grid_never_worse.{t.replace(',', '_')}.below"] = sum(1 for cg, b in gridwork.values() if cg[t] < b)
    keys["e5x.cells_with_work"] = len(rows) - sum(1 for r in rows if r[0].endswith("(no controlled work)"))
    keys["e5x.cells_without_work"] = sum(1 for r in rows if r[0].endswith("(no controlled work)"))
    keys["e5x.random.seeds"] = len(SEEDS)
    keys["e5x.count.above_floor"] = above
    keys["e5x.count.below_floor"] = below
    keys["e5x.count.mode_better"] = better
    for kk, vv in cost_count.items():
        keys[f"e5x.count.cost.{kk}"] = vv
    keys["e5x.floor"] = floor
    keys["e5x.cells"] = len(rows)
    (out / "numbers.json").write_text(json.dumps(keys, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    lines = [f"Voting analysis: {len(rows)} cells; ratio = controlled backtracking of the order / of voter 1,1,1;"
             f" floor {floor}", "",
             "| cell | group | N | M | controlled bt | mni calls | weight calls | controlled share | index | reverse |"
             " random min-max | grid best | grid worst | cost |",
             "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|---:|---:|"]
    f = lambda v: "–" if v is None else f"{v:.3f}"
    for cell, grp, N, M, base, mc, wc, sh, ratio in rows:
        rnd = [ratio[f"random_{s_}"] for s_ in SEEDS]
        rr = "–" if None in rnd else f"{min(rnd):.3f}–{max(rnd):.3f}"
        lines.append(f"| {cell} | {grp} | {N} | {M} | {base} | {mc} | {wc} | {sh:.3f} | {f(ratio['index'])} | "
                     f"{f(ratio['reverse'])} | {rr} | {f(ratio['grid_best'])} | {f(ratio['grid_worst'])} | {f(ratio.get('cost'))} |")
    lines += ["", f"compared orders above the floor: {above}; within the floor: {below}; better than the default "
                  f"beyond the floor: {better}", f"cost order (separate rule): {cost_count}"]
    if problems:
        lines += ["", "PROBLEMS:"] + problems
    (out / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    sys.exit(1 if problems or not rows else 0)


if __name__ == "__main__":
    main()
