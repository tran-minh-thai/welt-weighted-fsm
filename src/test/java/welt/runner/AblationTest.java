package welt.runner;

import org.junit.jupiter.api.Test;
import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ablation variants: one factor each, independent filters, unchanged result sets. */
class AblationTest {

    private static List<Object> factors(RunHarness.Config c) {
        return List.of(c.p1, c.p2, c.p2single, c.p3, c.p3AgainstResults, c.wext, c.voter.label(), c.weightOrder, c.bnb, c.tableReuse, c.tableThreshold, c.tableBuild);
    }

    private static int differences(RunHarness.Config a, RunHarness.Config b) {
        List<Object> x = factors(a), y = factors(b);
        int d = 0;
        for (int i = 0; i < x.size(); i++) if (!x.get(i).equals(y.get(i))) d++;
        return d;
    }

    @Test
    void eachVariantChangesTheDeclaredNumberOfFactors() {
        RunHarness.Config full = AblationMain.full(15, 336, 0);
        assertTrue(full.p1 && full.p2 && full.p2single && full.p3 && !full.p3AgainstResults && full.wext && full.weightOrder && full.bnb && full.tableReuse && full.tableThreshold && full.tableBuild,
                "full has every mechanism on");
        for (AblationMain.Variant v : AblationMain.VARIANTS) {
            RunHarness.Config c = AblationMain.full(15, 336, 0);
            v.change.accept(c);
            int expected = v.name.equals("full") ? 0 : (v.name.equals("noTable") || v.name.equals("noTableOptions")) ? 2 : 1;
            assertEquals(expected, differences(full, c), v.name);
        }
    }

    @Test
    void filtersAreIndependent() throws IOException {
        LabeledWeightedGraph g = new LgGraphReader().read(Path.of("datasets/email_eu.lg")).graph;
        RunHarness.Config onlyP2 = AblationMain.full(15, 336, 0);
        onlyP2.p1 = false;
        RunHarness.Result a = RunHarness.runOnce(g, onlyP2);
        assertEquals(0, a.m.prunedByP1);
        assertTrue(a.m.prunedByP2 > 0, "P2 must still prune when P1 is off");
        RunHarness.Config onlyP1 = AblationMain.full(15, 336, 0);
        onlyP1.p2 = false;
        RunHarness.Result b = RunHarness.runOnce(g, onlyP1);
        assertEquals(0, b.m.prunedByP2);
        assertTrue(b.m.prunedByP1 > 0, "P1 must still prune when P2 is off");
    }

    private static void allVariantsKeepResults(String file, int minSup, double minWeight) throws IOException {
        LabeledWeightedGraph g = new LgGraphReader().read(Path.of(file)).graph;
        Set<String> reference = AblationMain.codes(RunHarness.runOnce(g, AblationMain.full(minSup, minWeight, 0)));
        assertTrue(!reference.isEmpty(), "the reference result set must not be empty");
        for (AblationMain.Variant v : AblationMain.VARIANTS) {
            RunHarness.Config c = AblationMain.full(minSup, minWeight, 0);
            v.change.accept(c);
            RunHarness.Result r = RunHarness.runOnce(g, c);
            assertEquals("OK", r.status, v.name);
            assertEquals(reference, AblationMain.codes(r), v.name + " must return the result set of full");
        }
    }

    @Test
    void variantsKeepResultsOnEmailEu() throws IOException {
        allVariantsKeepResults("datasets/email_eu.lg", 15, 336);
    }

    @Test
    void variantsKeepResultsOnCiteseer() throws IOException {
        allVariantsKeepResults("datasets/citeseer.lg", 300, 96);
    }

    @Test
    void phaseTimersAreOffOutsideAblation() throws IOException {
        LabeledWeightedGraph g = new LgGraphReader().read(Path.of("datasets/email_eu.lg")).graph;
        RunHarness.Result plain = RunHarness.runOnce(g, new RunHarness.Config("WeLT", 15, 336));
        assertEquals(0, plain.m.canonNanos + plain.m.filterNanos + plain.m.mniNanos + plain.m.weightNanos);
        RunHarness.Result timed = RunHarness.runOnce(g, AblationMain.full(15, 336, 0));
        assertTrue(timed.m.canonNanos > 0 && timed.m.filterNanos > 0 && timed.m.mniNanos > 0 && timed.m.weightNanos > 0);
    }
}
