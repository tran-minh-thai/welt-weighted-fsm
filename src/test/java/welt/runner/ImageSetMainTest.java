package welt.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import welt.core.CanonicalCode;
import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.Metrics;
import welt.core.MniSupportCounter;
import welt.core.Pattern;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The image sets exported for the downstream features, against a brute-force enumeration of
 * every injective occurrence on small random graphs. The weights are drawn from {1, 2, 3} and
 * tau_w from {1, 2, 3, 4}, so edges of weight exactly tau_w occur (the >= boundary). Three
 * deliberately wrong exports are run through the same comparison and must be caught.
 */
class ImageSetMainTest {

    /** image(u) by enumerating every injective map that respects labels and edges of weight >= tauW. */
    static int[][] bruteForce(LabeledWeightedGraph g, Pattern p, double tauW, boolean injective) {
        int n = p.nodeCount();
        List<TreeSet<Integer>> img = new ArrayList<>();
        for (int i = 0; i < n; i++) img.add(new TreeSet<>());
        enumerate(g, p, tauW, injective, new int[n], 0, img);
        int[][] out = new int[n][];
        for (int i = 0; i < n; i++) out[i] = img.get(i).stream().mapToInt(Integer::intValue).toArray();
        return out;
    }

    private static void enumerate(LabeledWeightedGraph g, Pattern p, double tauW, boolean injective,
                                  int[] phi, int k, List<TreeSet<Integer>> img) {
        if (k == p.nodeCount()) {
            for (int i = 0; i < k; i++) img.get(i).add(phi[i]);
            return;
        }
        for (int v = 0; v < g.numVertices(); v++) {
            if (g.vertexLabel(v) != p.nodeLabel(k)) continue;
            boolean ok = true;
            for (int i = 0; i < k && ok; i++) {
                if (injective && phi[i] == v) ok = false;
                if (ok && p.hasEdge(i, k)) {
                    LabeledWeightedGraph.Adjacency e = g.edge(phi[i], v);
                    ok = e != null && e.edgeLabel == p.edgeLabelBetween(i, k) && e.weight >= tauW;
                }
            }
            if (!ok) continue;
            phi[k] = v;
            enumerate(g, p, tauW, injective, phi, k + 1, img);
        }
    }

    private static LabeledWeightedGraph randomGraph(Random rnd, int n, double density, int labels) {
        LabeledWeightedGraph g = new LabeledWeightedGraph(n);
        for (int v = 0; v < n; v++) g.setVertexLabel(v, rnd.nextInt(labels));
        for (int a = 0; a < n; a++) {
            for (int b = a + 1; b < n; b++) {
                if (rnd.nextDouble() < density) g.addUndirectedEdge(a, b, 0, 1 + rnd.nextInt(3));
            }
        }
        return g;
    }

    /** Connected patterns of 2 to 4 vertices on the given labels (paths, stars, triangle, cycle, chorded cycle). */
    private static List<Pattern> patterns(Random rnd, int labels) {
        int[][][] shapes = {
                {{0, 1, 0}},
                {{0, 1, 0}, {1, 2, 0}},
                {{0, 1, 0}, {1, 2, 0}, {0, 2, 0}},
                {{0, 1, 0}, {1, 2, 0}, {2, 3, 0}},
                {{0, 1, 0}, {0, 2, 0}, {0, 3, 0}},
                {{0, 1, 0}, {1, 2, 0}, {2, 3, 0}, {3, 0, 0}},
                {{0, 1, 0}, {1, 2, 0}, {2, 3, 0}, {3, 0, 0}, {0, 2, 0}},
        };
        List<Pattern> out = new ArrayList<>();
        for (int[][] s : shapes) {
            int n = 0;
            for (int[] e : s) n = Math.max(n, Math.max(e[0], e[1]) + 1);
            for (int rep = 0; rep < 3; rep++) {
                int[] lab = new int[n];
                for (int i = 0; i < n; i++) lab[i] = rep == 0 ? 0 : rnd.nextInt(labels); // rep 0: all-equal labels
                out.add(Pattern.of(lab, s));
            }
        }
        return out;
    }

    interface Export {
        int[][] images(LabeledWeightedGraph g, Pattern p, double tauW);
    }

    /** The export as ImageSetMain performs it: filter, then the exact image sets of the counter. */
    private static final Export REAL = (g, p, t) ->
            new MniSupportCounter(ImageSetMain.filterByWeight(g, t), new Metrics()).exactImageSets(p);

    /** Comparison statistics: cases compared, cases with a non-empty image, cases where the filter changed an image. */
    private static int[] compare(Export export, boolean injectiveOracle, long seed) {
        Random rnd = new Random(seed);
        int compared = 0, embedded = 0, filterBites = 0, mismatches = 0;
        for (int graph = 0; graph < 12; graph++) {
            int labels = 1 + graph % 2;
            LabeledWeightedGraph g = randomGraph(rnd, 7 + graph % 3, 0.45, labels);
            for (Pattern p0 : patterns(rnd, labels)) {
                Pattern p = ImageSetMain.decode(CanonicalCode.of(p0)); // the pipeline works on decoded patterns
                int[][] unfiltered = bruteForce(g, p, Double.NEGATIVE_INFINITY, true);
                for (double t : new double[]{1, 2, 3, 4}) {
                    int[][] expected = bruteForce(g, p, t, injectiveOracle);
                    int[][] got = export.images(g, p, t);
                    compared++;
                    if (expected[0].length > 0) embedded++;
                    if (!Arrays.deepEquals(expected, unfiltered)) filterBites++;
                    if (!Arrays.deepEquals(expected, got)) mismatches++;
                }
            }
        }
        return new int[]{compared, embedded, filterBites, mismatches};
    }

    @Test
    void imageSetsEqualBruteForceOnTheFilteredGraph() {
        int[] s = compare(REAL, true, 20261019L);
        System.out.printf("image sets vs brute force: %d cases, %d with an occurrence, %d where the filter changed an image, %d mismatches%n",
                s[0], s[1], s[2], s[3]);
        assertEquals(0, s[3], "mismatches between the export and the brute-force image sets");
        // the comparison must not be vacuous
        assertTrue(s[0] >= 1000, "cases compared: " + s[0]);
        assertTrue(s[1] >= 200, "cases with an occurrence: " + s[1]);
        assertTrue(s[2] >= 200, "cases where tau_w changes an image set: " + s[2]);
    }

    @Test
    void theComparisonCatchesInjectedFaults() {
        // strict > instead of >= in the filter (weights are integers, so +0.5 is "strictly above")
        Export strict = (g, p, t) -> REAL.images(g, p, t + 0.5);
        // no weight filter at all: the structural image sets of the unfiltered graph
        Export unfiltered = (g, p, t) -> REAL.images(g, p, Double.NEGATIVE_INFINITY);
        // a single occurrence per pattern instead of all of them
        Export firstOnly = (g, p, t) -> {
            int[][] all = REAL.images(g, p, t);
            int[][] one = new int[all.length][];
            for (int i = 0; i < all.length; i++) one[i] = all[i].length == 0 ? all[i] : new int[]{all[i][0]};
            return one;
        };
        int[] a = compare(strict, true, 20261019L);
        int[] b = compare(unfiltered, true, 20261019L);
        int[] c = compare(firstOnly, true, 20261019L);
        // and an oracle without injectivity must disagree with the (injective) export
        int[] d = compare(REAL, false, 20261019L);
        System.out.printf("injected faults caught: strict-filter %d, no-filter %d, first-occurrence %d, non-injective %d mismatches%n",
                a[3], b[3], c[3], d[3]);
        assertTrue(a[3] > 0, "a strict filter must be detected");
        assertTrue(b[3] > 0, "a missing filter must be detected");
        assertTrue(c[3] > 0, "image sets from one occurrence must be detected");
        assertTrue(d[3] > 0, "non-injective occurrences must be detected");
    }

    @Test
    void decodeInvertsTheCanonicalCode() {
        Random rnd = new Random(7);
        int checked = 0;
        for (Pattern p : patterns(rnd, 3)) {
            String code = CanonicalCode.of(p);
            Pattern q = ImageSetMain.decode(code);
            assertEquals(p.nodeCount(), q.nodeCount(), code);
            assertEquals(p.edgeCount(), q.edgeCount(), code);
            assertEquals(code, CanonicalCode.of(q), "re-encoding a decoded pattern");
            checked++;
        }
        assertEquals(21, checked);
        assertThrows(IllegalArgumentException.class, () -> ImageSetMain.decode("0 1 0 0 1;0 1 0 0 1;"));
        assertThrows(IllegalArgumentException.class, () -> ImageSetMain.decode("0 1 0 0 1;1 2 5 0 1;"));
        assertThrows(IllegalArgumentException.class, () -> ImageSetMain.decode("0 1 0 0;"));
    }

    @Test
    void exactImageSizesAreTheSizesOfTheImageSets() {
        Random rnd = new Random(11);
        LabeledWeightedGraph g = randomGraph(rnd, 9, 0.4, 2);
        MniSupportCounter c = new MniSupportCounter(g, new Metrics());
        for (Pattern p : patterns(rnd, 2)) {
            int[][] sets = c.exactImageSets(p);
            int[] sizes = new int[sets.length];
            for (int i = 0; i < sets.length; i++) sizes[i] = sets[i].length;
            assertArrayEquals(sizes, c.exactImageSizes(p));
        }
    }

    @Test
    void exportOfARealDumpHasNoEmptyImageSet(@TempDir Path dir) throws Exception {
        Path graph = Path.of("datasets/email_eu.lg");
        LabeledWeightedGraph g = new LgGraphReader().read(graph).graph;
        RunHarness.Result r = RunHarness.runOnce(g, new RunHarness.Config("WeLT", 15, 336));
        int n = PatternDump.write(dir, g, "email_eu.lg", "WeLT", 15, 336, r.patterns);
        assertTrue(n > 0);
        Path dump = dir.resolve(PatternDump.fileName("email_eu.lg", "WeLT", 15, 336));
        List<ImageSetMain.Dumped> dumped = ImageSetMain.readDump(dump);
        assertEquals(n, dumped.size());
        LabeledWeightedGraph f = ImageSetMain.filterByWeight(g, 336);
        MniSupportCounter c = new MniSupportCounter(f, new Metrics());
        int sets = 0;
        for (ImageSetMain.Dumped d : dumped) {
            for (int[] s : c.exactImageSets(ImageSetMain.decodeChecked(d))) {
                assertTrue(s.length > 0, "a result pattern (W >= tau_w) has an image in the filtered graph: " + d.code);
                sets++;
            }
        }
        assertTrue(sets >= n);
    }

    @Test
    void readDumpRejectsAFileThatIsNotADump(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("x.tsv");
        Files.writeString(f, "a\tb\n");
        assertThrows(IOException.class, () -> ImageSetMain.readDump(f));
    }
}
