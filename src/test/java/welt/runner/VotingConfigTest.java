package welt.runner;

import org.junit.jupiter.api.Test;
import welt.core.CanonicalCode;
import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.MiningEngine;
import welt.core.PivotVoter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The configured voter reaches the MNI variable order (engine counter) and the weight-aware
 * order reaches the weight check (strategy counter); neither changes the result set. On
 * email_eu, minSup 15, tau_w 336 the settings below give different backtracking counts, so the
 * inequalities are observable.
 */
class VotingConfigTest {

    private static LabeledWeightedGraph email() throws IOException {
        return new LgGraphReader().read(Path.of("datasets/email_eu.lg")).graph;
    }

    private static RunHarness.Result run(LabeledWeightedGraph g, PivotVoter voter, boolean worder) {
        RunHarness.Config cfg = new RunHarness.Config("WeLT", 15, 336);
        cfg.voter = voter;
        cfg.weightOrder = worder;
        return RunHarness.runOnce(g, cfg);
    }

    private static Set<String> codes(RunHarness.Result r) {
        Set<String> s = new TreeSet<>();
        for (MiningEngine.FrequentPattern fp : r.patterns) s.add(CanonicalCode.of(fp.pattern));
        return s;
    }

    @Test
    void voterReachesMniCountingAndKeepsResults() throws IOException {
        LabeledWeightedGraph g = email();
        RunHarness.Result degree = run(g, PivotVoter.DEGREE_ONLY, false);
        RunHarness.Result domain = run(g, PivotVoter.DOMAIN_ONLY, false);
        assertEquals(codes(degree), codes(domain), "voting must not change the result set");
        assertNotEquals(degree.m.backtrackMni, domain.m.backtrackMni,
                "the voter must change the MNI search (table construction and mining)");
        assertTrue(degree.m.voteNanos > 0 && domain.m.voteNanos > 0, "voting time must be measured");
    }

    @Test
    void voterReachesTheEngineCounter() throws IOException {
        // GraMi has no lookup table and no weight check: every MNI search runs on the engine
        // counter, so a different backtracking count can only come from the engine's voter.
        LabeledWeightedGraph g = email();
        RunHarness.Config degree = new RunHarness.Config("GraMi", 15, 336);
        degree.voter = PivotVoter.DEGREE_ONLY;
        RunHarness.Config domain = new RunHarness.Config("GraMi", 15, 336);
        domain.voter = PivotVoter.DOMAIN_ONLY;
        RunHarness.Result a = RunHarness.runOnce(g, degree);
        RunHarness.Result b = RunHarness.runOnce(g, domain);
        assertEquals(codes(a), codes(b));
        assertEquals(0, a.m.backtrackWeight + b.m.backtrackWeight, "GraMi performs no weight check");
        assertNotEquals(a.m.backtrackMni, b.m.backtrackMni, "the engine counter must use the configured voter");
    }

    @Test
    void weightOrderReachesWeightCheckAndKeepsResults() throws IOException {
        LabeledWeightedGraph g = email();
        RunHarness.Result on = run(g, PivotVoter.DEGREE_ONLY, true);
        RunHarness.Result off = run(g, PivotVoter.DEGREE_ONLY, false);
        assertEquals(codes(on), codes(off));
        assertEquals(on.m.backtrackMni, off.m.backtrackMni, "the weight order does not touch MNI");
        assertNotEquals(on.m.backtrackWeight, off.m.backtrackWeight,
                "the weight-aware order must change the weight check (strategy counter)");
    }

    @Test
    void backtrackSplitsIntoMniAndWeight() throws IOException {
        RunHarness.Result r = run(email(), PivotVoter.WELT_DEFAULT, true);
        assertTrue(r.m.backtrackMni > 0 && r.m.backtrackWeight > 0);
        assertEquals(r.m.backtrackNodes, r.m.backtrackMni + r.m.backtrackWeight);
    }

    @Test
    void voterSpecParsesAndLabels() {
        PivotVoter v = PivotVoter.parse("0.2, 0.6,0.2");
        assertEquals(0.2, v.alpha());
        assertEquals(0.6, v.beta());
        assertEquals("0.2;0.6;0.2", v.label());
        assertEquals("1;1;1", PivotVoter.WELT_DEFAULT.label());
        assertThrows(IllegalArgumentException.class, () -> PivotVoter.parse("1,1"));
        assertThrows(IllegalArgumentException.class, () -> PivotVoter.parse("1,-1,1"));
    }
}
