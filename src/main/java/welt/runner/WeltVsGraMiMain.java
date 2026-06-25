package welt.runner;

import welt.core.GraphIndex;
import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.Metrics;
import welt.core.MiningEngine;
import welt.core.MniSupportCounter;
import welt.strategy.GraMiStrategy;
import welt.strategy.WeLTStrategy;
import welt.strategy.WeightedLookupTable;

import java.nio.file.Path;
import java.util.List;

/**
 * FEASIBILITY EVALUATION: GraMi vs WeLT only (fast) — the core question is whether WeLT
 * reduces MINEmni (the EXPENSIVE mining-phase MNI) relative to GraMi, in the LOW threshold ×
 * HIGH τ_w region.
 *
 * <p>{@code java welt.runner.WeltVsGraMiMain <ds> <minSup> <minWeight> <budget>}
 */
public final class WeltVsGraMiMain {
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        int minSup = Integer.parseInt(args[1]);
        double minWeight = Double.parseDouble(args[2]);
        long budget = args.length > 3 ? Long.parseLong(args[3]) : 0;

        LabeledWeightedGraph g = new LgGraphReader().read(file).graph;
        GraphIndex index = new GraphIndex(g, minSup);

        // WeLT FIRST (to see immediately whether P2 engages) ----
        Metrics mw = new Metrics();
        MniSupportCounter cw = new MniSupportCounter(g, mw);
        cw.setSearchBudget(budget);
        WeightedLookupTable table = WeightedLookupTable.build(g, index, minSup, cw, mw);
        WeLTStrategy strat = new WeLTStrategy(g, minWeight, cw, table, mw);
        MiningEngine ew = new MiningEngine(g, mw);
        ew.setSearchBudget(budget);
        long t1 = System.nanoTime();
        List<MiningEngine.FrequentPattern> fw = ew.mine(minSup, strat);
        long tw = (System.nanoTime() - t1) / 1_000_000;
        long mineWnow = mw.mniIsoCalls - mw.tableBuildMniIsoCalls;
        System.out.printf("  [WeLT]  s=%d w=%.0f b=%s: freq=%d cand=%d MINEmni=%d P1=%d P2=%d %dms%n",
                minSup, minWeight, budget == 0 ? "∞" : String.valueOf(budget),
                fw.size(), mw.candidateCount, mineWnow, mw.prunedByP1, mw.prunedByP2, tw);

        // GraMi (τ_w-independent) — expensive baseline
        Metrics mg = new Metrics();
        MiningEngine eg = new MiningEngine(g, mg);
        eg.setSearchBudget(budget);
        long t0 = System.nanoTime();
        List<MiningEngine.FrequentPattern> fg = eg.mine(minSup, new GraMiStrategy());
        long tg = (System.nanoTime() - t0) / 1_000_000;

        long mineG = mg.mniIsoCalls - mg.tableBuildMniIsoCalls;
        long mineW = mw.mniIsoCalls - mw.tableBuildMniIsoCalls;
        System.out.printf("s=%d w=%.0f budget=%s | GraMi: freq=%d cand=%d MINEmni=%d %dms || "
                        + "WeLT: freq=%d cand=%d MINEmni=%d tbl=%d %dms || ΔMINEmni=%+d (%.0f%%) %s%n",
                minSup, minWeight, budget == 0 ? "∞" : String.valueOf(budget),
                fg.size(), mg.candidateCount, mineG, tg,
                fw.size(), mw.candidateCount, mineW, mw.tableBuildMniIsoCalls, tw,
                mineW - mineG, mineG == 0 ? 0.0 : 100.0 * (mineW - mineG) / mineG,
                (fw.size() < fg.size() ? "[P2 engage]" : "[P2 has not pruned any frequent pattern]"));
    }
}
