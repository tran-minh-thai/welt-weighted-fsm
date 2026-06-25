package welt.runner;

import welt.core.GraphIndex;
import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.Metrics;
import welt.core.MiningEngine;
import welt.core.MniSupportCounter;
import welt.strategy.WeLTStrategy;
import welt.strategy.WeightedLookupTable;

import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;

/**
 * τ_w THRESHOLD SWEEP (WeLT only, NO expensive baselines): sweeps τ_w from HIGH downward by
 * weight percentile of the dataset, printing {@code freq/cand/P1/P2/MINEmni/time}. Goal: find
 * the τ_w at which P2 starts to ENGAGE (P2>0, small MINEmni, fast) — the region where WeLT wins.
 *
 * <p>{@code java welt.runner.WeltSweepMain <ds> <minSup> <budget>}
 */
public final class WeltSweepMain {
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        int minSup = Integer.parseInt(args[1]);
        long budget = args.length > 2 ? Long.parseLong(args[2]) : 0;

        LabeledWeightedGraph g = new LgGraphReader().read(file).graph;
        GraphIndex index = new GraphIndex(g, minSup);

        // collect distinct edge weights to compute percentiles
        TreeSet<Double> ws = new TreeSet<>();
        double wmin = Double.MAX_VALUE, wmax = -Double.MAX_VALUE;
        for (int u = 0; u < g.numVertices(); u++) {
            for (LabeledWeightedGraph.Adjacency a : g.neighbors(u)) {
                if (a.to > u) { ws.add(a.weight); wmin = Math.min(wmin, a.weight); wmax = Math.max(wmax, a.weight); }
            }
        }
        Double[] sorted = ws.toArray(new Double[0]);
        System.out.printf("== τ_w sweep | %s | minSup=%d | budget=%s | ω∈[%.1f,%.1f] (%d distinct values) ==%n",
                file.getFileName(), minSup, budget == 0 ? "∞" : String.valueOf(budget), wmin, wmax, sorted.length);
        System.out.printf("%-10s %6s %6s %6s %6s %10s %9s%n", "τ_w", "freq", "cand", "P1", "P2", "MINEmni", "time(ms)");

        // τ_w grid = percentiles HIGH→low (where P2 tends to engage first)
        double[] pct = {0.999, 0.99, 0.97, 0.95, 0.90, 0.80, 0.70};
        for (double q : pct) {
            int idx = Math.min(sorted.length - 1, (int) Math.floor(q * (sorted.length - 1)));
            double tw = sorted[idx];
            Metrics m = new Metrics();
            MniSupportCounter cw = new MniSupportCounter(g, m);
            cw.setSearchBudget(budget);
            WeightedLookupTable table = WeightedLookupTable.build(g, index, minSup, cw, m);
            WeLTStrategy strat = new WeLTStrategy(g, tw, cw, table, m);
            MiningEngine ew = new MiningEngine(g, m);
            ew.setSearchBudget(budget);
            long t0 = System.nanoTime();
            List<MiningEngine.FrequentPattern> fw = ew.mine(minSup, strat);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            long mine = m.mniIsoCalls - m.tableBuildMniIsoCalls;
            System.out.printf("%-10.1f %6d %6d %6d %6d %10d %9d  %s%n",
                    tw, fw.size(), m.candidateCount, m.prunedByP1, m.prunedByP2, mine, ms,
                    m.prunedByP2 > 0 ? "<-- P2 ENGAGE" : "");
        }
    }
}
