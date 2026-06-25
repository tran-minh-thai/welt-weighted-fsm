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
 * REGRESSION TEST for OWGraMi (must always be GREEN): the FWS set produced by
 * OWGraMi must EXACTLY EQUAL
 *   { S ∈ GraMi-frequent(minSup) : W_v(S) ≥ minWeight },
 * where W_v(S) = max over occurrences of (the minimum assigned vertex weight).
 *
 * <p><b>Note:</b> this set DIFFERS from the WEGM/WeLT oracle (vertex weight ≠ edge
 * weight). Purpose of the test: confirm that the pre-prune (weightyTriples) does not
 * wrongly discard patterns satisfying the W_v condition, and that acceptFrequent
 * (embedsWithMinVertexWeight) classifies correctly.
 *
 * <p>Vertex weights use {@link welt.core.VertexWeightAssigner#AVERAGE_INCIDENT_EDGE}
 * (default) — this must be consistent with the actual run configuration.
 */
class OWGraMiOracleTest {

    private static final Path DATASET = Path.of("datasets/citeseer.lg");

    private LabeledWeightedGraph graph() throws IOException {
        return new LgGraphReader().read(DATASET).graph;
    }

    /**
     * Oracle: GraMi-frequent, then post-filtered by W_v(S) ≥ minWeight using
     * embedsWithMinVertexWeight (exact check, independent of OWGraMiStrategy).
     */
    private Set<String> oracle(LabeledWeightedGraph g, int minSup, double minWeight) {
        Metrics m = new Metrics();
        List<MiningEngine.FrequentPattern> frequent =
                new MiningEngine(g, m).mine(minSup, new GraMiStrategy());
        MniSupportCounter counter = new MniSupportCounter(g, m);
        Set<String> codes = new TreeSet<>();
        for (MiningEngine.FrequentPattern fp : frequent) {
            if (counter.embedsWithMinVertexWeight(fp.pattern, minWeight)) {
                codes.add(CanonicalCode.of(fp.pattern));
            }
        }
        return codes;
    }

    private Set<String> owgrami(LabeledWeightedGraph g, int minSup, double minWeight) {
        Metrics m = new Metrics();
        MniSupportCounter counter = new MniSupportCounter(g, m);
        OWGraMiStrategy strategy = new OWGraMiStrategy(g, minWeight, counter, m);
        List<MiningEngine.FrequentPattern> fps =
                new MiningEngine(g, m).mine(minSup, strategy);
        Set<String> codes = new TreeSet<>();
        for (MiningEngine.FrequentPattern fp : fps) codes.add(CanonicalCode.of(fp.pattern));
        return codes;
    }

    private void check(int minSup, double minWeight) throws IOException {
        LabeledWeightedGraph g = graph();
        assertEquals(oracle(g, minSup, minWeight), owgrami(g, minSup, minWeight),
                "OWGraMi(minSup=" + minSup + ", minWeight=" + minWeight
                        + ") must match the GraMi + W_v post-filter oracle");
    }

    // Vertex-weight thresholds are lower than edge-weight thresholds because vw = average of incident edges
    // → many vertices have an average below their strongest incident edge.
    @Test void s500_wv0() throws IOException { check(500, 0); }
    @Test void s500_wv40() throws IOException { check(500, 40); }
    @Test void s500_wv60() throws IOException { check(500, 60); }
    @Test void s400_wv40() throws IOException { check(400, 40); }
    @Test void s400_wv60() throws IOException { check(400, 60); }
    @Test void s300_wv0() throws IOException { check(300, 0); }
    @Test void s300_wv40() throws IOException { check(300, 40); }
    @Test void s300_wv60() throws IOException { check(300, 60); }
}
