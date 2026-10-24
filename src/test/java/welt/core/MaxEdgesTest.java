package welt.core;

import org.junit.jupiter.api.Test;
import welt.runner.RunHarness;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A size bound returns exactly the patterns of the unbounded run that have at most that many edges. */
class MaxEdgesTest {

    private static Set<String> codes(RunHarness.Result r, int maxEdges) {
        Set<String> s = new TreeSet<>();
        for (MiningEngine.FrequentPattern fp : r.patterns)
            if (maxEdges == 0 || fp.pattern.edgeCount() <= maxEdges) s.add(CanonicalCode.of(fp.pattern));
        return s;
    }

    @Test
    void boundedRunIsTheRestrictionOfTheUnboundedRun() throws IOException {
        LabeledWeightedGraph g = new LgGraphReader().read(Path.of("datasets/email_eu.lg")).graph;
        int largest = 0, compared = 0;
        for (String algo : List.of("GraMi", "WeLT")) {
            RunHarness.Result full = RunHarness.runOnce(g, new RunHarness.Config(algo, 12, 336));
            for (MiningEngine.FrequentPattern fp : full.patterns) largest = Math.max(largest, fp.pattern.edgeCount());
            for (int k = 1; k <= 3; k++) {
                RunHarness.Config c = new RunHarness.Config(algo, 12, 336);
                c.maxEdges = k;
                RunHarness.Result b = RunHarness.runOnce(g, c);
                assertEquals(codes(full, k), codes(b, 0), algo + " maxEdges=" + k);
                assertTrue(b.m.generated <= full.m.generated, algo + " maxEdges=" + k);
                compared++;
            }
        }
        assertEquals(6, compared);
        assertTrue(largest > 3, "the cell must have patterns above the bounds tested: largest " + largest);
    }
}
