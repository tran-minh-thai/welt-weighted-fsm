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
 * REGRESSION TEST for WEGM (must always be GREEN): the FWS set produced by WEGM must
 * EXACTLY EQUAL
 *   { S ∈ GraMi-frequent(minSup) : W(S) ≥ minWeight }.
 *
 * <p>WEGM and WeLT use the SAME definition of W(S) (edge bottleneck, anti-monotone), so
 * the result sets must be identical — differing only in speed (WEGM lacks the structural
 * filter P1). This test confirms that WEGM's simple P2 filter is a SOUND NECESSARY
 * CONDITION (no FWS patterns missed) and that {@code acceptFrequent} correctly rejects
 * patterns below the threshold.
 */
class WEGMOracleTest {

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

    private Set<String> wegm(LabeledWeightedGraph g, int minSup, double minWeight) {
        Metrics m = new Metrics();
        MniSupportCounter counter = new MniSupportCounter(g, m);
        WEGMStrategy strategy = new WEGMStrategy(g, minWeight, counter, m);
        List<MiningEngine.FrequentPattern> fps =
                new MiningEngine(g, m).mine(minSup, strategy);
        Set<String> codes = new TreeSet<>();
        for (MiningEngine.FrequentPattern fp : fps) codes.add(CanonicalCode.of(fp.pattern));
        return codes;
    }

    private void check(int minSup, double minWeight) throws IOException {
        LabeledWeightedGraph g = graph();
        assertEquals(oracle(g, minSup, minWeight), wegm(g, minSup, minWeight),
                "WEGM(minSup=" + minSup + ", minWeight=" + minWeight + ") must match the GraMi + W post-filter oracle");
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
