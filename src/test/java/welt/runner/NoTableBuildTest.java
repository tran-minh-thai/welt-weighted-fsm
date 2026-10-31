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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WeLT with tableBuild off: no table, the per-edge-type bound instead, WeLT's voter and order kept. */
class NoTableBuildTest {

    private static Set<String> codes(RunHarness.Result r) {
        Set<String> s = new TreeSet<>();
        for (MiningEngine.FrequentPattern fp : r.patterns) s.add(CanonicalCode.of(fp.pattern));
        return s;
    }

    @Test
    void sameResultsNoTableEdgeTypeBound() throws IOException {
        LabeledWeightedGraph g = new LgGraphReader().read(Path.of("datasets/email_eu.lg")).graph;
        RunHarness.Config full = AblationMain.full(10, 336, 0);
        RunHarness.Config off = AblationMain.full(10, 336, 0);
        off.tableBuild = false;
        RunHarness.Result a = RunHarness.runOnce(g, full), b = RunHarness.runOnce(g, off);
        assertEquals("OK", b.status);
        assertTrue(a.m.resultCount > 0);
        assertEquals(codes(a), codes(b), "the result set must not depend on the table");
        assertEquals(0, b.tableSize);
        assertEquals(0, b.m.tableBuildCandidates);
        assertEquals(0, b.m.prunedByP1 + b.m.prunedByP2);
        assertTrue(b.m.prunedByEdgeType > 0, "the per-edge-type bound must act");
        assertTrue(a.m.tableBuildCandidates > 0 && a.m.prunedByEdgeType == 0);
        assertEquals(full.voter.label(), off.voter.label());
        assertTrue(off.weightOrder && off.wext && off.bnb);
        assertTrue(!full.voter.label().equals(PivotVoter.DEGREE_ONLY.label()), "WeLT's voter is not the degree-only one");
    }

    @Test
    void algorithmOfItsOwnEqualsTheAblationVariant() throws IOException {
        LabeledWeightedGraph g = new LgGraphReader().read(Path.of("datasets/email_eu.lg")).graph;
        RunHarness.Config variant = AblationMain.full(10, 336, 0);
        variant.tableBuild = false;
        variant.phaseTiming = false;
        RunHarness.Config algo = BenchmarkMain.configure(new RunHarness.Config("WeLT-noTableBuild", 10, 336));
        RunHarness.Result a = RunHarness.runOnce(g, variant), b = RunHarness.runOnce(g, algo);
        assertEquals(codes(a), codes(b));
        assertEquals(a.m.candidateCount, b.m.candidateCount);
        assertEquals(a.m.mniIsoCalls, b.m.mniIsoCalls);
        assertEquals(a.m.backtrackNodes, b.m.backtrackNodes);
        assertEquals(variant.voter.label(), algo.voter.label());
        assertTrue(algo.weightOrder && algo.wext && algo.bnb && algo.p3);
        assertEquals("baseline", BenchmarkMain.voterSource("EdgeTypeBound"));
        assertEquals("default", BenchmarkMain.voterSource("WeLT-noTableBuild"));
    }

    @Test
    void gateFollowsTheConfiguration() throws IOException {
        LabeledWeightedGraph g = new LgGraphReader().read(Path.of("datasets/email_eu.lg")).graph;
        RunHarness.Config gated = AblationMain.full(12, 336, 0), ungated = AblationMain.full(12, 336, 0);
        gated.tableBuild = false;
        ungated.tableBuild = false;
        ungated.wext = false;
        RunHarness.Result a = RunHarness.runOnce(g, gated), b = RunHarness.runOnce(g, ungated);
        assertEquals(codes(a), codes(b));
        assertTrue(b.m.extended > a.m.extended, "without the gate more patterns are extended");
    }
}
