package welt.strategy;

import org.junit.jupiter.api.Test;
import welt.core.CanonicalCode;
import welt.core.GraphIndex;
import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.Metrics;
import welt.core.MiningEngine;
import welt.core.MniSupportCounter;
import welt.core.Pattern;
import welt.runner.RunHarness;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two cost options of the lookup table (kept supports, threshold table) change the work, not
 * the search: same result set, same generated / counted / frequent / extended patterns and the
 * same P1, P2, P3 decisions, with fewer MNI or weight embedding searches.
 */
class TableCostOptionsTest {

    private static LabeledWeightedGraph graph(String ds) throws IOException {
        return new LgGraphReader().read(Path.of("datasets/" + ds)).graph;
    }

    private static RunHarness.Result run(LabeledWeightedGraph g, int minSup, double minWeight, boolean reuse, boolean threshold) {
        RunHarness.Config c = new RunHarness.Config("WeLT", minSup, minWeight);
        c.tableReuse = reuse;
        c.tableThreshold = threshold;
        return RunHarness.runOnce(g, c);
    }

    private static Set<String> codes(RunHarness.Result r) {
        Set<String> s = new TreeSet<>();
        for (MiningEngine.FrequentPattern fp : r.patterns) s.add(CanonicalCode.of(fp.pattern));
        return s;
    }

    private static long[] search(RunHarness.Result r) {
        return new long[]{r.m.generated, r.m.candidateCount, r.m.mniFrequent, r.m.resultCount, r.m.extended,
                r.m.prunedByP1, r.m.prunedByP2, r.m.prunedByP2Single, r.m.prunedByApriori, r.tableSize};
    }

    private void sameSearchLessWork(String ds, int minSup, double minWeight) throws IOException {
        LabeledWeightedGraph g = graph(ds);
        RunHarness.Result base = run(g, minSup, minWeight, false, false);
        assertEquals("OK", base.status);
        assertTrue(base.m.resultCount > 0, ds + ": the cell must have results");
        for (boolean reuse : new boolean[]{false, true}) {
            for (boolean thr : new boolean[]{false, true}) {
                RunHarness.Result r = run(g, minSup, minWeight, reuse, thr);
                String what = ds + " reuse=" + reuse + " threshold=" + thr;
                assertEquals(codes(base), codes(r), what + ": result set");
                assertArrayEquals(search(base), search(r), what + ": search counters");
                long mineBase = base.m.mniIsoCalls - base.m.tableBuildMniIsoCalls;
                long mine = r.m.mniIsoCalls - r.m.tableBuildMniIsoCalls;
                assertEquals(base.m.tableBuildMniIsoCalls, r.m.tableBuildMniIsoCalls, what + ": the build counts the same patterns");
                if (reuse) {
                    assertTrue(r.m.supportReused > 0, what + ": no support was reused");
                    assertTrue(mine < mineBase, what + ": mining MNI searches " + mine + " not below " + mineBase);
                } else {
                    assertEquals(0, r.m.supportReused);
                    assertEquals(mineBase, mine, what + ": mining MNI searches");
                }
                if (thr) {
                    assertEquals(r.tableSize, r.m.tableBuildWeightIsoCalls, what + ": one weight check per table entry");
                    assertTrue(r.m.weightIsoCalls < base.m.weightIsoCalls, what + ": weight searches not reduced");
                } else {
                    assertEquals(0, r.m.weightReused);
                    assertEquals(base.m.tableBuildWeightIsoCalls, r.m.tableBuildWeightIsoCalls);
                }
            }
        }
    }

    @Test void emailEu10() throws IOException { sameSearchLessWork("email_eu.lg", 10, 336); }
    @Test void emailEu8() throws IOException { sameSearchLessWork("email_eu.lg", 8, 336); }
    @Test void citeseer300() throws IOException { sameSearchLessWork("citeseer.lg", 300, 96); }
    @Test void lastfm600() throws IOException { sameSearchLessWork("lastfm.lg", 600, 40); }

    /** With P2 off, failing 2-edge paths reach the weight check: the threshold table must answer it correctly. */
    @Test
    void thresholdAnswersWithP2Off() throws IOException {
        LabeledWeightedGraph g = graph("email_eu.lg");
        RunHarness.Config base = new RunHarness.Config("WeLT", 10, 336);
        base.p2 = false;
        RunHarness.Config thr = new RunHarness.Config("WeLT", 10, 336);
        thr.p2 = false;
        thr.tableThreshold = true;
        RunHarness.Result b = RunHarness.runOnce(g, base), t = RunHarness.runOnce(g, thr);
        assertEquals(codes(b), codes(t));
        assertArrayEquals(search(b), search(t));
        assertTrue(t.m.weightReused > 0);
        // some reused answers are "fails": fewer accepted 2-edge paths than reused checks
        long accepted2 = t.patterns.stream().filter(fp -> fp.pattern.edgeCount() == 2).count();
        assertTrue(t.m.weightReused > accepted2, "every reused weight check passed: " + t.m.weightReused + " vs " + accepted2);
    }

    private static WeightedLookupTable table(LabeledWeightedGraph g, int minSup, double threshold, boolean keep) {
        Metrics m = new Metrics();
        WeightedLookupTable.Options o = new WeightedLookupTable.Options();
        o.keepSupports = keep;
        o.threshold = threshold;
        return WeightedLookupTable.build(g, new GraphIndex(g, minSup), minSup, new MniSupportCounter(g, m), m, o);
    }

    /** Labels of the vertices of g. */
    private static int[] labels(LabeledWeightedGraph g) {
        return java.util.stream.IntStream.range(0, g.numVertices()).map(g::vertexLabel).distinct().sorted().toArray();
    }

    @Test
    void thresholdTableRefusesAnotherWeight() throws IOException {
        LabeledWeightedGraph g = graph("email_eu.lg");
        WeightedLookupTable t = table(g, 10, 336, false);
        Pattern recorded = null;
        for (int a : labels(g)) for (int b : labels(g)) for (int c : labels(g)) {
            Pattern path = Pattern.of(new int[]{a, b, c}, new int[][]{{0, 1, 0}, {1, 2, 0}});
            if (recorded == null && t.knownWeightOk(path, 336) != null) recorded = path;
        }
        assertNotNull(recorded, "the threshold table records no 2-edge path");
        Pattern path = recorded;
        assertThrows(IllegalStateException.class, () -> t.prune(path, 300, new Metrics(), true, true, true));
        assertThrows(IllegalStateException.class, () -> t.knownWeightOk(path, 300));
        assertEquals(new MniSupportCounter(g, new Metrics()).embedsWithMinWeight(path, 336), t.knownWeightOk(path, 336));
    }

    @Test
    void keptSupportsAreForTheirMinSupOnly() throws IOException {
        LabeledWeightedGraph g = graph("email_eu.lg");
        WeightedLookupTable t = table(g, 10, Double.NaN, true);
        Pattern edge = Pattern.singleEdge(0, 0, 0);
        String code = CanonicalCode.of(edge);
        assertNotNull(t.knownSupport(edge, code, 10));
        assertNull(t.knownSupport(edge, code, 11));
        assertNull(table(g, 10, Double.NaN, false).knownSupport(edge, code, 10));
    }

    /** Domains are mapped through an isomorphism when the miner's vertex order differs from the table's. */
    @Test
    void domainsFollowTheVertexOrder() throws IOException {
        LabeledWeightedGraph g = graph("email_eu.lg");
        int minSup = 10;
        WeightedLookupTable t = table(g, minSup, Double.NaN, true);
        MniSupportCounter fresh = new MniSupportCounter(g, new Metrics());
        int checked = 0;
        for (int a : labels(g)) for (int b : labels(g)) for (int c : labels(g)) {
            if (a >= c) continue; // a != c: the two vertex orders differ
            Pattern fwd = Pattern.of(new int[]{a, b, c}, new int[][]{{0, 1, 0}, {1, 2, 0}});
            Pattern rev = Pattern.of(new int[]{c, b, a}, new int[][]{{0, 1, 0}, {1, 2, 0}});
            String code = CanonicalCode.of(fwd);
            assertEquals(code, CanonicalCode.of(rev));
            MniSupportCounter.SupportResult kf = t.knownSupport(fwd, code, minSup);
            MniSupportCounter.SupportResult kr = t.knownSupport(rev, code, minSup);
            if (kf == null || kf.domains == null) continue;
            MniSupportCounter.SupportResult df = fresh.supportWithDomains(fwd, minSup, null);
            MniSupportCounter.SupportResult dr = fresh.supportWithDomains(rev, minSup, null);
            assertEquals(df.mni, kf.mni);
            assertEquals(dr.mni, kr.mni);
            for (int i = 0; i < 3; i++) {
                assertEquals(sorted(df.domains[i]), sorted(kf.domains[i]), "forward order, vertex " + i);
                assertEquals(sorted(dr.domains[i]), sorted(kr.domains[i]), "reversed order, vertex " + i);
            }
            checked++;
        }
        assertTrue(checked >= 5, "too few frequent paths with two end labels to check: " + checked);
    }

    private static List<Integer> sorted(int[] a) {
        int[] c = a.clone();
        Arrays.sort(c);
        return Arrays.stream(c).boxed().toList();
    }

    @Test
    void isomorphismOfSmallPatterns() {
        Pattern p = Pattern.of(new int[]{1, 2, 3}, new int[][]{{0, 1, 0}, {1, 2, 5}});
        Pattern q = Pattern.of(new int[]{3, 2, 1}, new int[][]{{0, 1, 5}, {1, 2, 0}});
        assertArrayEquals(new int[]{2, 1, 0}, WeightedLookupTable.isomorphism(p, q));
        Pattern wrongLabel = Pattern.of(new int[]{3, 2, 1}, new int[][]{{0, 1, 0}, {1, 2, 5}});
        assertNull(WeightedLookupTable.isomorphism(p, wrongLabel));
        assertNull(WeightedLookupTable.isomorphism(p, Pattern.singleEdge(1, 2, 0)));
    }
}
