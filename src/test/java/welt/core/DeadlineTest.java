package welt.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The time limit must interrupt a SINGLE long embedding search, not only the loop over
 * candidate values. A 13-vertex path cannot embed into the complete graph K12 (injectivity),
 * and proving it explores on the order of 10^8 partial assignments.
 */
class DeadlineTest {

    private static final int N = 12;

    private static LabeledWeightedGraph completeGraph() {
        LabeledWeightedGraph g = new LabeledWeightedGraph(N);
        for (int v = 0; v < N; v++) g.setVertexLabel(v, 0);
        for (int a = 0; a < N; a++)
            for (int b = a + 1; b < N; b++) g.addUndirectedEdge(a, b, 0, 1.0);
        return g;
    }

    private static Pattern path(int vertices) {
        int[] labels = new int[vertices];
        int[][] edges = new int[vertices - 1][];
        for (int i = 0; i < vertices - 1; i++) edges[i] = new int[]{i, i + 1, 0};
        return Pattern.of(labels, edges);
    }

    @Test
    void weightCheckStopsAtTheDeadline() {
        MniSupportCounter c = new MniSupportCounter(completeGraph(), new Metrics());
        long t0 = System.nanoTime();
        c.setDeadline(t0 + 100_000_000L); // 100 ms
        assertFalse(c.embedsWithMinWeight(path(N + 1), 0.0));
        double ms = (System.nanoTime() - t0) / 1e6;
        assertTrue(c.isTimedOut(), "the deadline must be reported");
        assertTrue(ms < 2000, "the weight search must stop near the deadline, took " + ms + " ms");
    }

    @Test
    void mniSearchStopsAtTheDeadline() {
        MniSupportCounter c = new MniSupportCounter(completeGraph(), new Metrics());
        long t0 = System.nanoTime();
        c.setDeadline(t0 + 100_000_000L);
        assertEquals(MniSupportCounter.INFREQUENT, c.support(path(N + 1), 1));
        double ms = (System.nanoTime() - t0) / 1e6;
        assertTrue(c.isTimedOut());
        assertTrue(ms < 2000, "the MNI search must stop near the deadline, took " + ms + " ms");
    }
}
