# -*- coding: utf-8 -*-
"""
WeLT paper — figure & table generator (Google Colab ready, self-contained).

Numbering note: Figure 1 in the paper is the running-example graph (drawn
inline with TikZ), so these external data figures are numbered from 2.

Run the whole cell. It produces, in the current working directory:
    Fig2_efficiency_comparison.pdf     RQ1 (candidates) + RQ2 (runtime), 2x3 log panels
    Fig3_memory_comparison.pdf         RQ3 peak memory (log bars, OOM marked)
    Fig4_ablation_study.pdf       RQ4 per-component ablation (normalised bars)
    Fig5_scalability_comparison.pdf    large/dense graphs (log bars, convergence)
and prints English booktabs LaTeX tables to the output.

All experimental data are embedded below, so no upload is needed.
Notation follows the journal style: proper math subscripts (tau_s -> $\\tau_s$),
never hyphenated forms; English scientific terminology throughout.
"""

import numpy as np
import matplotlib as mpl
import matplotlib.pyplot as plt
from matplotlib.patches import Patch
from matplotlib.lines import Line2D

# ----------------------------------------------------------------------------
# Publication style: serif body, Computer-Modern math, embedded TrueType fonts.
# ----------------------------------------------------------------------------
mpl.rcParams.update({
    "font.family": "serif",
    "font.serif": ["Times New Roman", "Nimbus Roman", "DejaVu Serif"],
    "mathtext.fontset": "cm",
    "font.size": 10,
    "axes.labelsize": 11,
    "axes.titlesize": 11,
    "legend.fontsize": 9,
    "xtick.labelsize": 9,
    "ytick.labelsize": 9,
    "axes.linewidth": 0.7,
    "lines.linewidth": 1.7,
    "grid.linewidth": 0.5,
    "savefig.dpi": 300,
    "savefig.bbox": "tight",
    "pdf.fonttype": 42,   # editable TrueType, journals prefer this
    "ps.fonttype": 42,
})

# Consistent, colour-blind-safe style per algorithm (colour + line + marker).
ALGOS = ["GraMi", "WEGM", "WeLT"]
STYLE = {
    "GraMi": dict(color="#4477AA", ls="--", marker="^"),
    "WEGM":  dict(color="#EE6677", ls="-.", marker="s"),
    "WeLT":  dict(color="#228833", ls="-",  marker="o", lw=2.3),
}
TIME_LIMIT_MS = 60 * 60 * 1000     # 60-minute per-run limit
MEM_CEIL_MB   = 14000              # heap ceiling used to draw OOM/TO bars

# ----------------------------------------------------------------------------
# DATA (measured). ms / value = None means the run did NOT finish (timeout/OOM).
# ----------------------------------------------------------------------------
# RQ1 candidates + RQ2 runtime. tau_s sorted ascending. cand is always recorded
# (partial when timed out); ms is None on timeout.
EFF = {
    "CiteSeer": {
        "tau":  [100, 150, 200, 250, 300, 400],
        "cand": {"GraMi": [159247, 86461, 549, 53, 30, 20],
                 "WEGM":  [166440, 90490, 535, 43, 20, 10],
                 "WeLT":  [35, 32, 29, 19, 19, 15]},
        "ms":   {"GraMi": [None, None, 29666, 79, 7, 4],
                 "WEGM":  [None, None, 27997, 59, 6, 2],
                 "WeLT":  [4, 3, 2, 1, 2, 2]},
    },
    "email-Eu-core": {
        "tau":  [6, 8, 10, 12, 15, 20],
        "cand": {"GraMi": [2234005, 332614, 4835, 831, 240, 90],
                 "WEGM":  [1849742, 234437, 3775, 610, 137, 40],
                 "WeLT":  [709, 441, 327, 212, 171, 78]},
        "ms":   {"GraMi": [None, None, 8216, 380, 12, 3],
                 "WEGM":  [None, None, 6054, 312, 10, 3],
                 "WeLT":  [91, 21, 9, 4, 3, 1]},
    },
    "LastFM Asia": {
        "tau":  [200, 300, 400, 500, 600],
        "cand": {"GraMi": [205080, 134900, 113256, 104544, 84041],
                 "WEGM":  [219538, 139047, 115996, 108259, 85312],
                 "WeLT":  [130, 98, 69, 62, 45]},
        "ms":   {"GraMi": [None, None, None, None, None],
                 "WEGM":  [None, None, None, None, None],
                 "WeLT":  [57, 54, 54, 61, 55]},
    },
}

# RQ3 peak memory (MB). (value, is_oom); value None with is_oom -> ran out of memory.
MEM = [
    (r"CiteSeer" + "\n" + r"$\tau_s{=}100$", {"GraMi": (8483, False), "WEGM": (9992, False),  "WeLT": (35, False)}),
    (r"CiteSeer" + "\n" + r"$\tau_s{=}200$", {"GraMi": (595, False),  "WEGM": (603, False),   "WeLT": (426, False)}),
    (r"email-Eu" + "\n" + r"$\tau_s{=}6$",   {"GraMi": (None, True),  "WEGM": (None, True),   "WeLT": (256, False)}),
    (r"email-Eu" + "\n" + r"$\tau_s{=}10$",  {"GraMi": (1314, False), "WEGM": (1489, False),  "WeLT": (929, False)}),
    (r"LastFM"   + "\n" + r"$\tau_s{=}600$", {"GraMi": (10062, False),"WEGM": (12189, False), "WeLT": (88, False)}),
]

# RQ4 ablation. Variants disable one component of full WeLT. cand = candidate
# subgraphs evaluated; bt = backtrack nodes. All variants share the same #FWS.
ABL_VARIANTS = ["WeLT (full)", "w/o lookup table", "w/o pivot voting", "w/o weight-aware order"]
ABL = [
    (r"CiteSeer, $\tau_s{=}200$",       {"cand": [29, 31, 29, 29],   "bt": [22743, 25657, 22743, 22743]}),
    (r"email-Eu-core, $\tau_s{=}12$",   {"cand": [212, 375, 212, 212], "bt": [12204, 15780, 11066, 12302]}),
]

# Scalability (large/dense graphs). Runtime ms; None -> timeout.
SCAL_GRAPHS = [r"MiCo" + "\n" + r"$\tau_s{=}14{,}000$",
               r"GitHub" + "\n" + r"$\tau_s{=}24{,}000$",
               r"STRING " + r"$E.\,coli$" + "\n" + r"$\tau_s{=}1{,}850$"]
SCAL = {"GraMi": [8785, 800621, None], "WEGM": [8785, 791129, None], "WeLT": [8731, 792346, None]}


# ----------------------------------------------------------------------------
# Helpers
# ----------------------------------------------------------------------------
def _legend_handles():
    h = []
    for a in ALGOS:
        s = STYLE[a]
        h.append(Line2D([0], [0], color=s["color"], ls=s["ls"], marker=s["marker"],
                        lw=s.get("lw", 1.7), ms=6, label=a))
    return h


def _plot_line(ax, x, y, to, algo, ceil):
    """Plot one algorithm's series; open markers flag non-completion (timeout)."""
    s = STYLE[algo]
    yy = [ceil if v is None else v for v in y]
    ax.plot(x, yy, color=s["color"], ls=s["ls"], lw=s.get("lw", 1.7), zorder=2)
    for xi, vi, ti in zip(x, yy, to):
        ax.plot(xi, vi, marker=s["marker"], ms=5.6, zorder=3,
                color=s["color"], mec=s["color"], mew=1.2,
                mfc=("white" if ti else s["color"]))


# ----------------------------------------------------------------------------
# Fig 1 — efficiency (RQ1 candidates, RQ2 runtime)
# ----------------------------------------------------------------------------
def fig1_efficiency():
    datasets = list(EFF.keys())
    fig, axes = plt.subplots(2, 3, figsize=(11, 6.2))
    for j, ds in enumerate(datasets):
        d = EFF[ds]
        x = d["tau"]
        to = {a: [v is None for v in d["ms"][a]] for a in ALGOS}
        # top: candidate subgraphs evaluated
        axc = axes[0, j]
        for a in ALGOS:
            _plot_line(axc, x, d["cand"][a], to[a], a, ceil=None)
        axc.set_yscale("log")
        axc.set_title(ds)
        axc.grid(True, which="major", ls=":", alpha=0.5)
        if j == 0:
            axc.set_ylabel("Candidate subgraphs\nevaluated (log scale)")
        # bottom: runtime
        axt = axes[1, j]
        for a in ALGOS:
            _plot_line(axt, x, d["ms"][a], to[a], a, ceil=TIME_LIMIT_MS)
        axt.set_yscale("log")
        axt.axhline(TIME_LIMIT_MS, color="0.4", ls=":", lw=1)
        axt.grid(True, which="major", ls=":", alpha=0.5)
        axt.set_xlabel(r"Support threshold $\tau_s$")
        if j == 0:
            axt.set_ylabel("Runtime (ms, log scale)")
    # annotate the headline speedup on CiteSeer at tau_s = 200
    ax = axes[1, 0]
    ax.annotate(r"$\approx$14,000$\times$", xy=(200, 27997), xytext=(250, 1200),
                fontsize=10, ha="center",
                arrowprops=dict(arrowstyle="->", lw=0.9, color="0.3"))
    axes[1, 0].text(0.97, 0.93, "open marker = did not finish\n(timeout or out of memory)",
                    transform=axes[1, 0].transAxes, ha="right", va="top",
                    fontsize=7.5, color="0.35")
    fig.legend(handles=_legend_handles(), loc="upper center", ncol=3,
               frameon=False, bbox_to_anchor=(0.5, 1.02))
    fig.tight_layout(rect=[0, 0, 1, 0.97])
    fig.savefig("Fig2_efficiency_comparison.pdf")
    plt.close(fig)


# ----------------------------------------------------------------------------
# Fig 2 — peak memory (RQ3)
# ----------------------------------------------------------------------------
def fig2_memory():
    labels = [c for c, _ in MEM]
    x = np.arange(len(labels))
    w = 0.26
    fig, ax = plt.subplots(figsize=(8.4, 4.3))
    for k, a in enumerate(ALGOS):
        vals, ooms = [], []
        for _, row in MEM:
            v, oom = row[a]
            vals.append(MEM_CEIL_MB if v is None else v)
            ooms.append(oom)
        bars = ax.bar(x + (k - 1) * w, vals, w, color=STYLE[a]["color"],
                      edgecolor="black", linewidth=0.4, label=a, zorder=2)
        for b, oom in zip(bars, ooms):
            if oom:
                b.set_hatch("///")
                b.set_alpha(0.55)
                ax.text(b.get_x() + b.get_width() / 2, MEM_CEIL_MB * 1.04,
                        "OOM", ha="center", va="bottom", fontsize=7.5, color="0.25")
    ax.set_yscale("log")
    ax.set_ylabel("Peak memory (MB, log scale)")
    ax.set_xticks(x)
    ax.set_xticklabels(labels)
    ax.grid(True, axis="y", which="major", ls=":", alpha=0.5)
    ax.legend(frameon=False, ncol=3, loc="upper center", bbox_to_anchor=(0.5, 1.12))
    fig.tight_layout()
    fig.savefig("Fig3_memory_comparison.pdf")
    plt.close(fig)


# ----------------------------------------------------------------------------
# Fig 3 — ablation (RQ4): candidates and backtrack, normalised to full WeLT
# ----------------------------------------------------------------------------
def fig3_ablation():
    fig, axes = plt.subplots(1, 2, figsize=(10, 4.1))
    palette = ["#228833", "#CC6677", "#DDAA33", "#88CCEE"]
    metrics = [("cand", "Candidate subgraphs (relative to full)"),
               ("bt",   "Backtrack nodes (relative to full)")]
    xpos = np.arange(len(ABL))
    w = 0.2
    for ax, (key, ylab) in zip(axes, metrics):
        for vi, vname in enumerate(ABL_VARIANTS):
            heights = []
            for _, row in ABL:
                base = row[key][0]
                heights.append(row[key][vi] / base)
            ax.bar(xpos + (vi - 1.5) * w, heights, w, color=palette[vi],
                   edgecolor="black", linewidth=0.4, label=vname, zorder=2)
        ax.axhline(1.0, color="0.4", ls=":", lw=1)
        ax.set_ylabel(ylab)
        ax.set_xticks(xpos)
        ax.set_xticklabels([c for c, _ in ABL])
        ax.grid(True, axis="y", which="major", ls=":", alpha=0.5)
    axes[0].legend(frameon=False, fontsize=8, loc="upper left")
    fig.tight_layout()
    fig.savefig("Fig4_ablation_study.pdf")
    plt.close(fig)


# ----------------------------------------------------------------------------
# Fig 4 — scalability: runtime on large/dense graphs (convergence)
# ----------------------------------------------------------------------------
def fig4_scalability():
    x = np.arange(len(SCAL_GRAPHS))
    w = 0.26
    fig, ax = plt.subplots(figsize=(7.6, 4.2))
    for k, a in enumerate(ALGOS):
        vals = [TIME_LIMIT_MS if v is None else v for v in SCAL[a]]
        to = [v is None for v in SCAL[a]]
        bars = ax.bar(x + (k - 1) * w, vals, w, color=STYLE[a]["color"],
                      edgecolor="black", linewidth=0.4, label=a, zorder=2)
        for b, t in zip(bars, to):
            if t:
                b.set_hatch("///")
                b.set_alpha(0.55)
                ax.text(b.get_x() + b.get_width() / 2, TIME_LIMIT_MS * 1.04,
                        "T.O.", ha="center", va="bottom", fontsize=7.5, color="0.25")
    ax.set_yscale("log")
    ax.set_ylabel("Runtime (ms, log scale)")
    ax.set_xticks(x)
    ax.set_xticklabels(SCAL_GRAPHS)
    ax.grid(True, axis="y", which="major", ls=":", alpha=0.5)
    ax.legend(frameon=False, ncol=3, loc="upper center", bbox_to_anchor=(0.5, 1.12))
    fig.tight_layout()
    fig.savefig("Fig5_scalability_comparison.pdf")
    plt.close(fig)


# ----------------------------------------------------------------------------
# English LaTeX tables (booktabs). Printed to output, ready to paste.
# ----------------------------------------------------------------------------
def _num(v):
    return "T.O." if v is None else f"{v:,}"

def print_tables():
    print("\n% ===== Table: candidate subgraphs evaluated =====")
    print(r"\begin{tabular}{@{}ll" + "r" * 3 + r"@{}}\toprule")
    print(r"Dataset & $\tau_s$ & GraMi & WEGM & WeLT \\ \midrule")
    for ds, d in EFF.items():
        for i, t in enumerate(sorted(range(len(d["tau"])), key=lambda i: -d["tau"][i])):
            row = [d["cand"][a][t] for a in ALGOS]
            best = min(row)
            cells = " & ".join(rf"\textbf{{{v:,}}}" if v == best else f"{v:,}" for v in row)
            head = ds if i == 0 else ""
            print(f"{head} & {d['tau'][t]} & {cells} \\\\")
        print(r"\midrule")
    print(r"\bottomrule\end{tabular}")

    print("\n% ===== Table: runtime (ms); T.O. = did not finish within 60 min =====")
    print(r"\begin{tabular}{@{}ll" + "r" * 3 + r"@{}}\toprule")
    print(r"Dataset & $\tau_s$ & GraMi & WEGM & WeLT \\ \midrule")
    for ds, d in EFF.items():
        for i, t in enumerate(sorted(range(len(d["tau"])), key=lambda i: -d["tau"][i])):
            row = [d["ms"][a][t] for a in ALGOS]
            head = ds if i == 0 else ""
            cells = " & ".join(_num(v) for v in row)
            print(f"{head} & {d['tau'][t]} & {cells} \\\\")
        print(r"\midrule")
    print(r"\bottomrule\end{tabular}")

    print("\n% ===== Table: peak memory (MB); OOM = out of memory =====")
    print(r"\begin{tabular}{@{}l" + "r" * 3 + r"@{}}\toprule")
    print(r"Configuration & GraMi & WEGM & WeLT \\ \midrule")
    for label, row in MEM:
        lab = label.replace("\n", " ")
        cells = " & ".join("OOM" if row[a][0] is None else f"{row[a][0]:,}" for a in ALGOS)
        print(f"{lab} & {cells} \\\\")
    print(r"\bottomrule\end{tabular}")


# ----------------------------------------------------------------------------
# Run
# ----------------------------------------------------------------------------
if __name__ == "__main__":
    fig1_efficiency()
    fig2_memory()
    fig3_ablation()
    fig4_scalability()
    print_tables()
    outputs = ["Fig2_efficiency_comparison.pdf", "Fig3_memory_comparison.pdf",
               "Fig4_ablation_study.pdf", "Fig5_scalability_comparison.pdf"]
    print("\nSaved:", ", ".join(outputs))
    try:
        from google.colab import files  # auto-download on Colab
        for f in outputs:
            files.download(f)
    except Exception:
        pass
