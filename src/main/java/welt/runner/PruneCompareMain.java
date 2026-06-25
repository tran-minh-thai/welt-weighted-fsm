package welt.runner;

import welt.core.GraphIndex;
import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.Metrics;
import welt.core.MiningEngine;
import welt.core.MniSupportCounter;
import welt.strategy.GraMiStrategy;
import welt.strategy.MiningStrategy;
import welt.strategy.WeLTStrategy;
import welt.strategy.WeightedLookupTable;

import java.nio.file.Path;
import java.util.List;

/**
 * MILESTONE 5 — measures the effectiveness of the EARLY-PRUNING MECHANISMS in sequence.
 *
 * <p>Mechanism #1 (full downward closure): toggled via {@link MiningEngine#setAprioriPruning}.
 * Prints {@code candidateCount} (number of MNI calls) and {@code isoCallCount} for both modes
 * to show the reduction. The result set ({@code #FWS}) MUST be equal.
 *
 * <p>Usage: {@code java welt.runner.PruneCompareMain datasets/citeseer.lg 300 90}
 */
public final class PruneCompareMain {

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args.length > 0 ? args[0] : "datasets/citeseer.lg");
        int minSup = args.length > 1 ? Integer.parseInt(args[1]) : 300;
        double minWeight = args.length > 2 ? Double.parseDouble(args[2]) : 90.0;

        LabeledWeightedGraph g = new LgGraphReader().read(file).graph;
        System.out.printf("== Early pruning | %s | minSup=%d minWeight=%.1f | %d vertices %d edges ==%n",
                file.getFileName(), minSup, minWeight, g.numVertices(), g.numEdges());
        System.out.printf("%-30s %5s %8s %9s %11s %8s%n",
                "configuration", "#FWS", "candMNI", "isoCall", "backtrack", "cut#1");

        System.out.println("-- Mechanism #1 (downward closure) on GraMi --");
        runGraMi(g, minSup, false);
        runGraMi(g, minSup, true);
        System.out.println("-- WeLT: #1 (apriori) then +#2 (weight-aware edge ordering) --");
        runWeLT(g, minSup, minWeight, false, false);
        runWeLT(g, minSup, minWeight, true, false);
        runWeLT(g, minSup, minWeight, true, true);
        System.out.println("(candMNI=number of patterns calling MNI; cut#1=candidates pruned by downward closure; backtrack isolates the impact of #2 since the MNI part is invariant.)");
    }

    private static void runGraMi(LabeledWeightedGraph g, int minSup, boolean apriori) {
        Metrics m = new Metrics();
        MiningEngine engine = new MiningEngine(g, m);
        engine.setAprioriPruning(apriori);
        List<MiningEngine.FrequentPattern> fps = engine.mine(minSup, new GraMiStrategy());
        System.out.printf("%-30s %5d %8d %9d %11d %8d%n",
                "GraMi #1=" + (apriori ? "ON" : "OFF"),
                fps.size(), m.candidateCount, m.isoCallCount, m.backtrackNodes, m.prunedByApriori);
    }

    private static void runWeLT(LabeledWeightedGraph g, int minSup, double minWeight,
                                boolean apriori, boolean weightAware) {
        Metrics m = new Metrics();
        GraphIndex index = new GraphIndex(g, minSup);
        MniSupportCounter counter = new MniSupportCounter(g, m);
        counter.setWeightAwareOrdering(weightAware);
        WeightedLookupTable table = WeightedLookupTable.build(g, index, minSup, counter, m);
        MiningStrategy strat = new WeLTStrategy(g, minWeight, counter, table, m);
        MiningEngine engine = new MiningEngine(g, m);
        engine.setAprioriPruning(apriori);
        List<MiningEngine.FrequentPattern> fps = engine.mine(minSup, strat);
        System.out.printf("%-30s %5d %8d %9d %11d %8d%n",
                "WeLT #1=" + (apriori ? "ON" : "OFF") + " #2=" + (weightAware ? "ON" : "OFF"),
                fps.size(), m.candidateCount, m.isoCallCount, m.backtrackNodes, m.prunedByApriori);
    }
}
