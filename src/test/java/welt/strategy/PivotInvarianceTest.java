package welt.strategy;

import org.junit.jupiter.api.Test;
import welt.core.CanonicalCode;
import welt.core.GraphIndex;
import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.Metrics;
import welt.core.MiningEngine;
import welt.core.MniSupportCounter;
import welt.core.PivotVoter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Correctness invariance of the pivot-selection voting (pivot section): regardless of
 * the ablation configuration (α,β,γ), WeLT must produce EXACTLY ONE FWS set — because
 * acceptance is decided by exact MNI and W, while the voting only changes the
 * traversal order (performance).
 *
 * <p>This is the empirical validation of the "does not affect correctness" statement
 * in the paper, and also the basis for the ablation study (RQ4).
 */
class PivotInvarianceTest {

    private static final Path DATASET = Path.of("datasets/citeseer.lg");

    private Set<String> runWeLT(LabeledWeightedGraph g, int minSup, double minWeight, PivotVoter voter) {
        Metrics m = new Metrics();
        GraphIndex index = new GraphIndex(g, minSup);
        MniSupportCounter counter = new MniSupportCounter(g, m);
        counter.setVoter(voter); // ablation: change how the matching start vertex is chosen
        WeightedLookupTable table = WeightedLookupTable.build(g, index, minSup, counter, m);
        WeLTStrategy strat = new WeLTStrategy(g, minWeight, counter, table, m);
        MiningEngine engine = new MiningEngine(g, m);
        engine.setVoter(voter); // apply the ablation to the internal MNI counting as well
        List<MiningEngine.FrequentPattern> fps = engine.mine(minSup, strat);
        Set<String> codes = new TreeSet<>();
        for (MiningEngine.FrequentPattern fp : fps) codes.add(CanonicalCode.of(fp.pattern));
        return codes;
    }

    @Test
    void allAblationConfigsGiveSameResultSet() throws IOException {
        LabeledWeightedGraph g = new LgGraphReader().read(DATASET).graph;
        int minSup = 300;
        double minWeight = 90;

        Set<String> ref = runWeLT(g, minSup, minWeight, PivotVoter.DEGREE_ONLY);
        assertEquals(ref, runWeLT(g, minSup, minWeight, PivotVoter.DOMAIN_ONLY),
                "DOMAIN_ONLY must yield the same FWS set");
        assertEquals(ref, runWeLT(g, minSup, minWeight, PivotVoter.WEIGHT_ONLY),
                "WEIGHT_ONLY must yield the same FWS set");
        assertEquals(ref, runWeLT(g, minSup, minWeight, PivotVoter.WELT_DEFAULT),
                "WELT_DEFAULT must yield the same FWS set");
        assertEquals(ref, runWeLT(g, minSup, minWeight, new PivotVoter(0.5, 0.3, 0.2)),
                "an arbitrary voting configuration must yield the same FWS set");
    }

    @Test
    void invarianceHoldsAtHighWeightThreshold() throws IOException {
        LabeledWeightedGraph g = new LgGraphReader().read(DATASET).graph;
        Set<String> ref = runWeLT(g, 400, 95, PivotVoter.DEGREE_ONLY);
        assertEquals(ref, runWeLT(g, 400, 95, PivotVoter.WELT_DEFAULT));
        assertEquals(ref, runWeLT(g, 400, 95, PivotVoter.WEIGHT_ONLY));
    }
}
