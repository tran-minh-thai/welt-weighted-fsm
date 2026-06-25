package welt.runner;

import welt.core.GraphIndex;
import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.Metrics;
import welt.core.MiningEngine;
import welt.core.MniSupportCounter;
import welt.core.PivotVoter;
import welt.strategy.WeLTStrategy;
import welt.strategy.WeightedLookupTable;

import java.nio.file.Path;
import java.util.List;

/**
 * MILESTONE 5 — ABLATION STUDY (RQ4) for extension-point pivot voting.
 *
 * <p>Runs WeLT with different {@link PivotVoter} configurations under the same setting
 * (minSup, minWeight) and prints metrics: the FWS set (must be IDENTICAL — correctness
 * invariant), together with {@code backtrackNodes}/{@code isoCallCount}/time (DIFFERENT —
 * measuring the performance contribution of each voting signal).
 *
 * <p>Usage: {@code java welt.runner.AblationMain datasets/citeseer.lg 300 90}
 */
public final class AblationMain {

    private static final String[] NAMES = {
            "DOMAIN_ONLY(1,0,0)", "DEGREE_ONLY(0,1,0)", "WEIGHT_ONLY(0,0,1)", "WELT_DEFAULT(1,1,1)"
    };
    private static final PivotVoter[] VOTERS = {
            PivotVoter.DOMAIN_ONLY, PivotVoter.DEGREE_ONLY, PivotVoter.WEIGHT_ONLY, PivotVoter.WELT_DEFAULT
    };

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args.length > 0 ? args[0] : "datasets/citeseer.lg");
        int minSup = args.length > 1 ? Integer.parseInt(args[1]) : 300;
        double minWeight = args.length > 2 ? Double.parseDouble(args[2]) : 90.0;

        LabeledWeightedGraph g = new LgGraphReader().read(file).graph;
        System.out.printf("== Extension-point pivot voting ablation | %s | minSup=%d minWeight=%.1f ==%n",
                file.getFileName(), minSup, minWeight);
        System.out.printf("%-22s %6s %12s %12s %8s%n",
                "voter(α,β,γ)", "#FWS", "backtrack", "isoCall", "time(ms)");

        long refFws = -1;
        for (int i = 0; i < VOTERS.length; i++) {
            Metrics m = new Metrics();
            GraphIndex index = new GraphIndex(g, minSup);
            MniSupportCounter counter = new MniSupportCounter(g, m);
            counter.setVoter(VOTERS[i]);
            WeightedLookupTable table = WeightedLookupTable.build(g, index, minSup, counter, m);
            WeLTStrategy strat = new WeLTStrategy(g, minWeight, counter, table, m);
            MiningEngine engine = new MiningEngine(g, m);
            engine.setVoter(VOTERS[i]); // ablation also applies to the internal MNI counting
            long t0 = System.nanoTime();
            List<MiningEngine.FrequentPattern> fps = engine.mine(minSup, strat);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf("%-22s %6d %12d %12d %8d%n",
                    NAMES[i], fps.size(), m.backtrackNodes, m.isoCallCount, ms);
            if (refFws < 0) refFws = fps.size();
            else if (refFws != fps.size()) {
                System.out.println("  !! WARNING: #FWS differs from the first configuration — correctness invariant violated!");
            }
        }
        System.out.println("(Every configuration MUST share the same #FWS — voting only changes performance, not the result.)");
    }
}
