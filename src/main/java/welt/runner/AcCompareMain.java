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

/**
 * Isolates the contribution of arc-consistency (AC-3) domain filtering to the MNI
 * counting cost: runs WeLT with AC OFF then ON under the same setting, printing
 * {@code isoCall}/{@code backtrack}/time. The result set must be IDENTICAL (AC preserves MNI).
 *
 * <p>{@code java welt.runner.AcCompareMain datasets/citeseer.lg 260 90}
 */
public final class AcCompareMain {
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        int minSup = Integer.parseInt(args[1]);
        double minWeight = Double.parseDouble(args[2]);
        LabeledWeightedGraph g = new LgGraphReader().read(file).graph;
        System.out.printf("== AC-3 for MNI counting | %s | minSup=%d minWeight=%.1f ==%n",
                file.getFileName(), minSup, minWeight);
        System.out.printf("%-12s %6s %10s %12s %9s%n", "AC", "#FWS", "isoCall", "backtrack", "time(ms)");
        run(g, minSup, minWeight, false);
        run(g, minSup, minWeight, true);
    }

    private static void run(LabeledWeightedGraph g, int minSup, double minWeight, boolean ac) {
        Metrics m = new Metrics();
        GraphIndex index = new GraphIndex(g, minSup);
        MniSupportCounter counter = new MniSupportCounter(g, m);
        counter.setArcConsistency(ac);
        long t0 = System.nanoTime();
        WeightedLookupTable table = WeightedLookupTable.build(g, index, minSup, counter, m);
        WeLTStrategy strat = new WeLTStrategy(g, minWeight, counter, table, m);
        MiningEngine engine = new MiningEngine(g, m);
        List<MiningEngine.FrequentPattern> fps = engine.mine(minSup, strat);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("%-12s %6d %10d %12d %9d%n",
                ac ? "ON" : "OFF", fps.size(), m.isoCallCount, m.backtrackNodes, ms);
    }
}
