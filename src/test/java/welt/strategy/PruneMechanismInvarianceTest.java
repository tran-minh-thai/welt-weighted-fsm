package welt.strategy;

import org.junit.jupiter.api.Test;
import welt.core.CanonicalCode;
import welt.core.GraphIndex;
import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.Metrics;
import welt.core.MiningEngine;
import welt.core.MniSupportCounter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Correctness invariance of the TWO early-pruning mechanisms (Milestone 5):
 * <ul>
 *   <li>Mechanism #1 — full decremental closure ({@link MiningEngine#setAprioriPruning}):
 *       on/off must yield the same FWS set (MNI anti-monotonicity ⇒ no patterns missed).</li>
 *   <li>Mechanism #2 — weight-aware edge ordering ({@link MniSupportCounter#setWeightAwareOrdering}):
 *       on/off must yield the same FWS set (only changes the matching order).</li>
 * </ul>
 */
class PruneMechanismInvarianceTest {

    private static final Path DATASET = Path.of("datasets/citeseer.lg");

    private Set<String> runWeLT(LabeledWeightedGraph g, int minSup, double minWeight,
                                boolean apriori, boolean weightAware) {
        Metrics m = new Metrics();
        GraphIndex index = new GraphIndex(g, minSup);
        MniSupportCounter counter = new MniSupportCounter(g, m);
        counter.setWeightAwareOrdering(weightAware);
        WeightedLookupTable table = WeightedLookupTable.build(g, index, minSup, counter, m);
        WeLTStrategy strat = new WeLTStrategy(g, minWeight, counter, table, m);
        MiningEngine engine = new MiningEngine(g, m);
        engine.setAprioriPruning(apriori);
        List<MiningEngine.FrequentPattern> fps = engine.mine(minSup, strat);
        Set<String> codes = new TreeSet<>();
        for (MiningEngine.FrequentPattern fp : fps) codes.add(CanonicalCode.of(fp.pattern));
        return codes;
    }

    private Set<String> runGraMi(LabeledWeightedGraph g, int minSup, boolean apriori) {
        Metrics m = new Metrics();
        MiningEngine engine = new MiningEngine(g, m);
        engine.setAprioriPruning(apriori);
        List<MiningEngine.FrequentPattern> fps = engine.mine(minSup, new GraMiStrategy());
        Set<String> codes = new TreeSet<>();
        for (MiningEngine.FrequentPattern fp : fps) codes.add(CanonicalCode.of(fp.pattern));
        return codes;
    }

    @Test
    void aprioriDoesNotChangeGraMiResult() throws IOException {
        LabeledWeightedGraph g = new LgGraphReader().read(DATASET).graph;
        assertEquals(runGraMi(g, 300, false), runGraMi(g, 300, true),
                "the decremental closure must not change the GraMi result set");
        assertEquals(runGraMi(g, 500, false), runGraMi(g, 500, true));
    }

    @Test
    void bothMechanismsDoNotChangeWeLTResult() throws IOException {
        LabeledWeightedGraph g = new LgGraphReader().read(DATASET).graph;
        Set<String> ref = runWeLT(g, 300, 90, false, false);
        assertEquals(ref, runWeLT(g, 300, 90, true, false), "#1 (apriori) invariant");
        assertEquals(ref, runWeLT(g, 300, 90, false, true), "#2 (weight-aware) invariant");
        assertEquals(ref, runWeLT(g, 300, 90, true, true), "#1+#2 invariant");
    }

    @Test
    void invarianceAtHighWeight() throws IOException {
        LabeledWeightedGraph g = new LgGraphReader().read(DATASET).graph;
        Set<String> ref = runWeLT(g, 400, 95, false, false);
        assertEquals(ref, runWeLT(g, 400, 95, true, true), "#1+#2 invariant at high τ_w");
    }
}
