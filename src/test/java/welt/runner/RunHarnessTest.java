package welt.runner;

import org.junit.jupiter.api.Test;
import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cost accounting of {@link RunHarness}: the reported runtime covers the index, the
 * strategy preprocessing (the WeLT lookup table) and mining; the table-construction
 * counters are populated for WeLT and zero for the baselines.
 */
class RunHarnessTest {

    private static LabeledWeightedGraph citeseer() throws IOException {
        return new LgGraphReader().read(Path.of("datasets/citeseer.lg")).graph;
    }

    private static RunHarness.Result run(LabeledWeightedGraph g, String algo) {
        return RunHarness.runOnce(g, new RunHarness.Config(algo, 300, 96));
    }

    private static void assertPhasesAddUp(RunHarness.Result r) {
        assertTrue(r.indexMs >= 0 && r.prepMs >= 0 && r.mineMs >= 0 && r.tableBuildMs >= 0,
                "negative phase time: index=" + r.indexMs + " prep=" + r.prepMs + " mine=" + r.mineMs);
        assertEquals(r.totalMs, r.indexMs + r.prepMs + r.mineMs, 1e-6, "totalMs must equal index + prep + mine");
        assertTrue(r.prepMs >= r.tableBuildMs, "the lookup table is part of the preprocessing phase");
    }

    @Test
    void weltRuntimeIncludesTableConstruction() throws IOException {
        RunHarness.Result r = run(citeseer(), "WeLT");
        assertPhasesAddUp(r);
        assertTrue(r.tableBuildMs > 0, "table construction must be timed");
        assertTrue(r.tableSize > 0, "citeseer at minSup 300 has frequent 2-edge paths");
        assertTrue(r.m.tableBuildCandidates >= r.tableSize, "every table entry was evaluated");
        assertTrue(r.m.tableBuildMniIsoCalls > 0);
        assertTrue(r.m.tableBuildWeightIsoCalls > 0, "MaxW binary searches are weight iso-calls");
        assertEquals(r.m.mniIsoCalls, r.mniIsoCallsMine() + r.m.tableBuildMniIsoCalls);
        assertEquals(r.m.candidateCount + r.m.tableBuildCandidates, r.candidatesTotal());
    }

    @Test
    void baselinesHaveNoTableCost() throws IOException {
        LabeledWeightedGraph g = citeseer();
        for (String algo : new String[]{"GraMi", "WEGM"}) {
            RunHarness.Result r = run(g, algo);
            assertPhasesAddUp(r);
            assertEquals(0.0, r.tableBuildMs, algo);
            assertEquals(0, r.tableSize, algo);
            assertEquals(0, r.m.tableBuildCandidates, algo);
            assertEquals(0, r.m.tableBuildMniIsoCalls, algo);
            assertEquals(0, r.m.tableBuildWeightIsoCalls, algo);
            assertEquals(r.m.mniIsoCalls, r.mniIsoCallsMine(), algo);
        }
    }

    @Test
    void sameResultSizeAsBeforeTheHarness() throws IOException {
        // Values printed by the pre-harness BenchmarkMain at commit b9b9709 for
        // citeseer, minSup 300, tau_w 96: #FWS GraMi 9, WEGM 5, WeLT 5; candMNI 30, 20, 19.
        LabeledWeightedGraph g = citeseer();
        assertEquals(9, run(g, "GraMi").patterns.size());
        assertEquals(5, run(g, "WEGM").patterns.size());
        RunHarness.Result w = run(g, "WeLT");
        assertEquals(5, w.patterns.size());
        assertEquals(19, w.m.candidateCount);
    }

    @Test
    void rowRejectsDuplicateColumnsAndCommas() {
        RunHarness.Row row = new RunHarness.Row().put("a", 1).put("b", 2.5);
        assertEquals("a,b", row.header());
        assertEquals("1,2.500", row.values());
        try {
            row.put("a", 3);
            throw new AssertionError("duplicate column accepted");
        } catch (IllegalStateException expected) {
            // ok
        }
        RunHarness.Row bad = new RunHarness.Row().put("x", "1,2");
        try {
            bad.values();
            throw new AssertionError("comma inside a value accepted");
        } catch (IllegalStateException expected) {
            // ok
        }
    }
}
