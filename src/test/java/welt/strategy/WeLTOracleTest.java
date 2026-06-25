package welt.strategy;

import org.junit.jupiter.api.Test;
import welt.core.CanonicalCode;
import welt.core.GraphIndex;
import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.Metrics;
import welt.core.MiningEngine;
import welt.core.MniSupportCounter;
import welt.core.Pattern;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CORE REGRESSION TEST for WeLT (must always be GREEN): the FWS set produced by WeLT
 * (double filter P1/P2 + exact evaluation) must EXACTLY EQUAL
 *   { S ∈ GraMi-frequent(minSup) : W(S) ≥ minWeight }.
 *
 * <p>This is the embodiment of the correctness theorem: the two filters are only
 * pre-filters and do not change the result set. The oracle uses {@link GraMiStrategy}
 * (cross-checked against the original GraMi at Milestone 2) plus a weight post-filter
 * via {@code embedsWithMinWeight}.
 *
 * <p>An additional INDEPENDENT validation in Python (against W(S) computed separately)
 * was run outside CI.
 */
class WeLTOracleTest {

    private static final Path DATASET = Path.of("datasets/citeseer.lg");

    private LabeledWeightedGraph graph() throws IOException {
        return new LgGraphReader().read(DATASET).graph;
    }

    /** Oracle: GraMi-frequent, then filtered by W(S) ≥ minWeight (exact evaluation). */
    private Set<String> oracle(LabeledWeightedGraph g, int minSup, double minWeight) {
        Metrics m = new Metrics();
        MiningEngine engine = new MiningEngine(g, m);
        List<MiningEngine.FrequentPattern> frequent = engine.mine(minSup, new GraMiStrategy());
        MniSupportCounter counter = new MniSupportCounter(g, m);
        Set<String> codes = new TreeSet<>();
        for (MiningEngine.FrequentPattern fp : frequent) {
            if (counter.embedsWithMinWeight(fp.pattern, minWeight)) {
                codes.add(CanonicalCode.of(fp.pattern));
            }
        }
        return codes;
    }

    /** Full WeLT: lookup table + double filter + exact evaluation. */
    private Set<String> welt(LabeledWeightedGraph g, int minSup, double minWeight) {
        Metrics m = new Metrics();
        GraphIndex index = new GraphIndex(g, minSup);
        MniSupportCounter counter = new MniSupportCounter(g, m);
        WeightedLookupTable table = WeightedLookupTable.build(g, index, minSup, counter, m);
        WeLTStrategy strat = new WeLTStrategy(g, minWeight, counter, table, m);
        MiningEngine engine = new MiningEngine(g, m);
        List<MiningEngine.FrequentPattern> fps = engine.mine(minSup, strat);
        Set<String> codes = new TreeSet<>();
        for (MiningEngine.FrequentPattern fp : fps) codes.add(CanonicalCode.of(fp.pattern));
        return codes;
    }

    private void check(int minSup, double minWeight) throws IOException {
        LabeledWeightedGraph g = graph();
        assertEquals(oracle(g, minSup, minWeight), welt(g, minSup, minWeight),
                "WeLT(minSup=" + minSup + ", minWeight=" + minWeight + ") must match the GraMi + W post-filter oracle");
    }

    @Test void s500_w0() throws IOException { check(500, 0); }
    @Test void s500_w95() throws IOException { check(500, 95); }
    @Test void s500_w98() throws IOException { check(500, 98); }
    @Test void s400_w90() throws IOException { check(400, 90); }
    @Test void s400_w98() throws IOException { check(400, 98); }
    @Test void s300_w0() throws IOException { check(300, 0); }
    @Test void s300_w95() throws IOException { check(300, 95); }
    @Test void s300_w98() throws IOException { check(300, 98); }
}
