package welt.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.Metrics;
import welt.core.MiningEngine;
import welt.core.MniSupportCounter;
import welt.core.Pattern;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact MNI and W of dumped result sets. */
class PatternDumpTest {

    /** Definition: the exact MNI is the largest k for which support(p, k) is not INFREQUENT. */
    private static int mniByThresholds(LabeledWeightedGraph g, Pattern p) {
        int k = 0;
        while (new MniSupportCounter(g, new Metrics()).support(p, k + 1) != MniSupportCounter.INFREQUENT) k++;
        return k;
    }

    @Test
    void exactMniMatchesTheThresholdDefinition() throws IOException {
        LabeledWeightedGraph g = new LgGraphReader().read(Path.of("datasets/email_eu.lg")).graph;
        RunHarness.Result r = RunHarness.runOnce(g, new RunHarness.Config("WeLT", 15, 336));
        MniSupportCounter exact = new MniSupportCounter(g, new Metrics());
        int above = 0;
        for (MiningEngine.FrequentPattern fp : r.patterns) {
            int e = exact.exactMni(fp.pattern);
            assertEquals(mniByThresholds(g, fp.pattern), e, "exact MNI of a result pattern");
            if (e > 15) above++;
        }
        assertTrue(above > 0, "some MNI must exceed minSup, otherwise early stopping would go unnoticed");
        // a pattern that does not embed: exact MNI 0
        Pattern absent = Pattern.of(new int[]{999, 998}, new int[][]{{0, 1, 0}});
        assertEquals(0, exact.exactMni(absent));
    }

    @Test
    void dumpWritesEveryResultWithExactValues(@TempDir Path dir) throws IOException {
        LabeledWeightedGraph g = new LgGraphReader().read(Path.of("datasets/citeseer.lg")).graph;
        RunHarness.Result r = RunHarness.runOnce(g, new RunHarness.Config("WeLT", 300, 96));
        int n = PatternDump.write(dir, g, "citeseer.lg", "WeLT", 300, 96, r.patterns);
        assertEquals(r.patterns.size(), n);
        List<String> lines = Files.readAllLines(dir.resolve("citeseer_WeLT_300_96.0.tsv"));
        assertEquals("code\tvertices\tedges\tmni\tweight", lines.get(0));
        assertEquals(n + 1, lines.size());
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split("\t");
            assertEquals(5, f.length, line);
            assertTrue(Integer.parseInt(f[3]) >= 300, "MNI of a result must reach minSup: " + line);
            assertTrue(Double.parseDouble(f[4]) >= 96, "W of a result must reach tau_w: " + line);
        }
    }
}
