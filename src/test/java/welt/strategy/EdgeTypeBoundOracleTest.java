package welt.strategy;

import org.junit.jupiter.api.Test;
import welt.core.CanonicalCode;
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
 * REGRESSION TEST for EdgeTypeBound (gated and ungated): the result set must EXACTLY EQUAL
 *   { S ∈ GraMi-frequent(minSup) : W(S) ≥ minWeight }.
 *
 * <p>EdgeTypeBound and WeLT use the SAME definition of W(S) (edge bottleneck, anti-monotone),
 * so the result sets must be identical — differing only in speed. This test confirms that the
 * per-edge-type bound and the weight-gated extension lose no result and that
 * {@code acceptFrequent} rejects patterns below the threshold.
 */
class EdgeTypeBoundOracleTest {

    private static final Path DATASET = Path.of("datasets/citeseer.lg");

    private LabeledWeightedGraph graph() throws IOException {
        return new LgGraphReader().read(DATASET).graph;
    }

    /** Oracle: GraMi-frequent, then post-filtered by W(S) ≥ minWeight. */
    private Set<String> oracle(LabeledWeightedGraph g, int minSup, double minWeight) {
        Metrics m = new Metrics();
        List<MiningEngine.FrequentPattern> frequent =
                new MiningEngine(g, m).mine(minSup, new GraMiStrategy());
        MniSupportCounter counter = new MniSupportCounter(g, m);
        Set<String> codes = new TreeSet<>();
        for (MiningEngine.FrequentPattern fp : frequent) {
            if (counter.embedsWithMinWeight(fp.pattern, minWeight)) {
                codes.add(CanonicalCode.of(fp.pattern));
            }
        }
        return codes;
    }

    private Set<String> wegm(LabeledWeightedGraph g, int minSup, double minWeight, boolean gated) {
        Metrics m = new Metrics();
        MniSupportCounter counter = new MniSupportCounter(g, m);
        EdgeTypeBoundStrategy strategy = new EdgeTypeBoundStrategy(g, minWeight, counter, m, gated);
        List<MiningEngine.FrequentPattern> fps =
                new MiningEngine(g, m).mine(minSup, strategy);
        assertEquals(0, m.budgetExceeded, "exact mode: no embedding search may be aborted");
        Set<String> codes = new TreeSet<>();
        for (MiningEngine.FrequentPattern fp : fps) codes.add(CanonicalCode.of(fp.pattern));
        return codes;
    }

    private void check(int minSup, double minWeight) throws IOException {
        LabeledWeightedGraph g = graph();
        Set<String> expected = oracle(g, minSup, minWeight);
        assertEquals(expected, wegm(g, minSup, minWeight, true),
                "EdgeTypeBound(minSup=" + minSup + ", minWeight=" + minWeight + ") must match the GraMi + W post-filter oracle");
        assertEquals(expected, wegm(g, minSup, minWeight, false),
                "EdgeTypeBound-ungated(minSup=" + minSup + ", minWeight=" + minWeight + ") must match the oracle");
    }

    @Test void s500_w0() throws IOException { check(500, 0); }
    @Test void s500_w95() throws IOException { check(500, 95); }
    @Test void s500_w98() throws IOException { check(500, 98); }
    @Test void s400_w90() throws IOException { check(400, 90); }
    @Test void s400_w98() throws IOException { check(400, 98); }
    @Test void s300_w0() throws IOException { check(300, 0); }
    @Test void s300_w95() throws IOException { check(300, 95); }
    @Test void s300_w98() throws IOException { check(300, 98); }

    /** The gate extends only patterns that reach the weight threshold; the ungated form extends every frequent one. */
    @Test
    void gateRestrictsExtension() throws IOException {
        LabeledWeightedGraph g = new LgGraphReader().read(Path.of("datasets/email_eu.lg")).graph;
        long[] extended = new long[2];
        long[] results = new long[2];
        for (int k = 0; k < 2; k++) {
            Metrics m = new Metrics();
            new MiningEngine(g, m).mine(15, new EdgeTypeBoundStrategy(g, 336, new MniSupportCounter(g, m), m, k == 0));
            extended[k] = m.extended;
            results[k] = m.resultCount;
        }
        assertEquals(results[0], results[1]);
        org.junit.jupiter.api.Assertions.assertTrue(extended[0] < extended[1],
                "gated " + extended[0] + " must extend fewer patterns than ungated " + extended[1]);
    }
}
