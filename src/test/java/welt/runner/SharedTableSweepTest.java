package welt.runner;

import org.junit.jupiter.api.Test;
import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A sweep with one shared lookup table answers every tau_w exactly like a fresh run. */
class SharedTableSweepTest {

    @Test
    void sharedTableMatchesFreshRunsAtEveryTau() throws IOException {
        LabeledWeightedGraph g = new LgGraphReader().read(Path.of("datasets/email_eu.lg")).graph;
        double[] taus = {100, 336, 800, 2000};
        RunHarness.Config cfg = BenchmarkMain.configure(new RunHarness.Config("WeLT", 15, taus[0]));
        SharedTableSweepMain.Sweep s = SharedTableSweepMain.sweep(g, 15, taus, cfg, 0);
        assertEquals("OK", s.status);
        assertEquals(taus.length, s.points.size());
        long distinctR = java.util.Arrays.stream(taus).count();
        java.util.Set<Long> rs = new java.util.HashSet<>();
        for (int i = 0; i < taus.length; i++) {
            RunHarness.Config fresh = BenchmarkMain.configure(new RunHarness.Config("WeLT", 15, taus[i]));
            RunHarness.Result r = RunHarness.runOnce(g, fresh);
            assertEquals(r.m.resultCount, (long) s.points.get(i)[1], "R at tau_w=" + taus[i]);
            assertEquals(r.m.candidateCount, (long) s.points.get(i)[2], "M at tau_w=" + taus[i]);
            rs.add(r.m.resultCount);
        }
        assertTrue(rs.size() > 1, "the grid must give different result sizes, otherwise the check is weak");
        assertTrue(s.tableMs > 0 && distinctR == 4);
    }
}
