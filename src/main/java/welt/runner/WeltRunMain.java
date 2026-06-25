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
 * Runs a SINGLE WeLT configuration (apriori + weight-aware enabled) and prints metrics + time.
 * Used to verify the n≤9 cap has been removed: it runs at LOW support thresholds.
 *
 * <p>{@code java welt.runner.WeltRunMain datasets/citeseer.lg 250 90}
 */
public final class WeltRunMain {
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        int minSup = Integer.parseInt(args[1]);
        double minWeight = Double.parseDouble(args[2]);

        LabeledWeightedGraph g = new LgGraphReader().read(file).graph;
        Metrics m = new Metrics();
        GraphIndex index = new GraphIndex(g, minSup);
        MniSupportCounter counter = new MniSupportCounter(g, m);
        counter.setWeightAwareOrdering(true);
        long t0 = System.nanoTime();
        WeightedLookupTable table = WeightedLookupTable.build(g, index, minSup, counter, m);
        WeLTStrategy strat = new WeLTStrategy(g, minWeight, counter, table, m);
        MiningEngine engine = new MiningEngine(g, m);
        engine.setAprioriPruning(true);
        List<MiningEngine.FrequentPattern> fps = engine.mine(minSup, strat);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        int maxEdges = 0;
        for (MiningEngine.FrequentPattern fp : fps) maxEdges = Math.max(maxEdges, fp.pattern.edgeCount());
        System.out.printf("WeLT s=%d w=%.0f: #FWS=%d (maxEdges=%d) candMNI=%d iso=%d apriori_cut=%d P1=%d P2=%d time=%dms%n",
                minSup, minWeight, fps.size(), maxEdges, m.candidateCount, m.isoCallCount,
                m.prunedByApriori, m.prunedByP1, m.prunedByP2, ms);
    }
}
