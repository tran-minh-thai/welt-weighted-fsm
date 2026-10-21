package welt.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import welt.runner.RunHarness;
import welt.strategy.GraMiStrategy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The retained-data counters, recomputed from their definitions on a graph built by hand: 5 disjoint
 * A-B edges and 4 disjoint C-C edges, all of weight 10, at minSup 3. The two single-edge patterns are
 * frequent; their three 2-edge extensions (B-A-B, A-B-A, C-C-C) are not, since no vertex has two
 * neighbours. The reduced domains of A-B hold 5 + 5 vertices, those of C-C 8 + 8 (both pattern
 * vertices take every C vertex), and both patterns are on the frontier at once: 26 vertex ids.
 */
class RetainedDataCountersTest {

    private static final int A = 0, B = 1, C = 2;

    @TempDir
    Path dir;

    private LabeledWeightedGraph graph() throws IOException {
        StringBuilder s = new StringBuilder("t # 0\n");
        for (int i = 0; i < 5; i++) s.append("v ").append(i).append(' ').append(A).append('\n');
        for (int i = 5; i < 10; i++) s.append("v ").append(i).append(' ').append(B).append('\n');
        for (int i = 10; i < 18; i++) s.append("v ").append(i).append(' ').append(C).append('\n');
        for (int i = 0; i < 5; i++) s.append("e ").append(i).append(' ').append(i + 5).append(" 10\n");
        for (int i = 10; i < 18; i += 2) s.append("e ").append(i).append(' ').append(i + 1).append(" 10\n");
        Path f = dir.resolve("hand.lg");
        Files.writeString(f, s.toString());
        return new LgGraphReader().read(f).graph;
    }

    private static int chars(Pattern... ps) {
        int n = 0;
        for (Pattern p : ps) n += CanonicalCode.of(p).length();
        return n;
    }

    @Test
    void graMiOnTheHandGraph() throws IOException {
        Metrics m = new Metrics();
        List<MiningEngine.FrequentPattern> fps = new MiningEngine(graph(), m).mine(3, new GraMiStrategy());
        assertEquals(2, fps.size());
        Pattern ab = Pattern.singleEdge(A, B, 0), cc = Pattern.singleEdge(C, C, 0);
        Pattern bab = Pattern.of(new int[]{B, A, B}, new int[][]{{0, 1, 0}, {1, 2, 0}});
        Pattern aba = Pattern.of(new int[]{A, B, A}, new int[][]{{0, 1, 0}, {1, 2, 0}});
        Pattern ccc = Pattern.of(new int[]{C, C, C}, new int[][]{{0, 1, 0}, {1, 2, 0}});
        assertEquals(5, m.generated);
        assertEquals(chars(ab, cc, bab, aba, ccc), m.visitedCodeChars);
        assertEquals(chars(ab, cc), m.memoCodeChars);
        assertEquals(26, m.frontierDomainIntsPeak);
        assertEquals(0, m.frontierDomainInts, "every frontier pattern is released once extended");
        assertEquals(0, m.tableKeptPatterns);
        assertEquals(0, m.tableDomainInts);
    }

    private RunHarness.Result welt(boolean reuse) throws IOException {
        RunHarness.Config c = new RunHarness.Config("WeLT", 3, 5);
        c.tableReuse = reuse;
        RunHarness.Result r = RunHarness.runOnce(graph(), c);
        assertEquals("OK", r.status);
        assertEquals(2, r.m.resultCount);
        return r;
    }

    /** Kept table domains are counted once, in the table, and not again on the frontier. */
    @Test
    void weltCountsKeptDomainsInTheTable() throws IOException {
        RunHarness.Result on = welt(true), off = welt(false);
        // kept: A-B, C-C (domains 26 ids) and the three infrequent 2-edge paths (no domains)
        assertEquals(5, on.m.tableKeptPatterns);
        assertEquals(on.m.tableBuildCandidates, on.m.tableKeptPatterns);
        assertEquals(26, on.m.tableDomainInts);
        assertEquals(0, on.m.frontierDomainIntsPeak, "both frontier patterns reuse the table's domains");
        assertEquals(0, off.m.tableKeptPatterns);
        assertEquals(0, off.m.tableDomainInts);
        assertEquals(26, off.m.frontierDomainIntsPeak);
        for (RunHarness.Result r : List.of(on, off)) assertEquals(0, r.m.frontierDomainInts);
    }

    /** On a real graph the counters are deterministic and the frontier accounting closes. */
    @Test
    void realGraphDeterministicAndClosed() throws IOException {
        LabeledWeightedGraph g = new LgGraphReader().read(Path.of("datasets/email_eu.lg")).graph;
        for (String algo : List.of("WeLT", "EdgeTypeBound", "GraMi")) {
            RunHarness.Result a = RunHarness.runOnce(g, new RunHarness.Config(algo, 12, 336));
            RunHarness.Result b = RunHarness.runOnce(g, new RunHarness.Config(algo, 12, 336));
            assertEquals("OK", a.status);
            assertTrue(a.m.frontierDomainIntsPeak > 0 && a.m.visitedCodeChars > 0 && a.m.memoCodeChars > 0, algo);
            assertEquals(0, a.m.frontierDomainInts, algo);
            long[] x = {a.m.visitedCodeChars, a.m.memoCodeChars, a.m.frontierDomainIntsPeak, a.m.tableKeptPatterns, a.m.tableDomainInts};
            long[] y = {b.m.visitedCodeChars, b.m.memoCodeChars, b.m.frontierDomainIntsPeak, b.m.tableKeptPatterns, b.m.tableDomainInts};
            org.junit.jupiter.api.Assertions.assertArrayEquals(x, y, algo);
        }
    }
}
