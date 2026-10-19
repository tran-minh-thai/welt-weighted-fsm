package welt.strategy;

import org.junit.jupiter.api.Test;
import welt.core.CanonicalCode;
import welt.core.LabeledWeightedGraph;
import welt.core.Metrics;
import welt.core.MiningEngine;
import welt.core.MniSupportCounter;
import welt.core.Pattern;

import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixed test of {@link WEGMStrategy} against the worked example of the WEGM article
 * (J. Inf. Telecommun. 9(4):543-558, 2025): the graph G of Figure 1 (p. 544), the domains of
 * Figures 3 and 4 (p. 548, 550), the weights of Figure 5 (p. 550) and the Max-method pruning
 * of p. 553 at w = 2.
 *
 * <p>Vertices u1..u12 are indices 0..11; labels P = 0, A = 1, D = 2, M = 3.
 */
class WEGMPaperExampleTest {

    private static final int P = 0, A = 1, D = 2, M = 3;
    private static final int[] LABEL = {P, A, D, P, A, P, A, P, M, D, M, D};
    private static final int[][] EDGES = { // u, v (1-based as in Figure 1), weight
            {1, 2, 3}, {2, 3, 2}, {3, 4, 5}, {4, 5, 3},
            {1, 6, 3}, {2, 7, 4}, {3, 8, 6}, {4, 9, 3}, {5, 10, 2},
            {6, 7, 7}, {7, 8, 3}, {8, 9, 9}, {9, 10, 1},
            {6, 11, 8}, {7, 12, 2}, {11, 12, 1}};

    private static LabeledWeightedGraph figure1() {
        LabeledWeightedGraph g = new LabeledWeightedGraph(12);
        for (int v = 0; v < 12; v++) g.setVertexLabel(v, LABEL[v]);
        for (int[] e : EDGES) g.addUndirectedEdge(e[0] - 1, e[1] - 1, 0, e[2]);
        return g;
    }

    private static Pattern edge(int la, int w, int lb) {
        return Pattern.of(new int[]{la, lb}, new int[][]{{0, 1, w}});
    }

    /** S1 = P -3- A -2- D (Figure 5). */
    private static final Pattern S1 = Pattern.of(new int[]{P, A, D}, new int[][]{{0, 1, 3}, {1, 2, 2}});

    @Test
    void domainsOfFigures3And4() {
        LabeledWeightedGraph g = WEGMStrategy.weightLabeledGraph(figure1());
        MniSupportCounter c = new MniSupportCounter(g, new Metrics());
        // Figure 4: P-3-A has v1 = {u1, u4, u8} (u6 invalid: its edge to A weighs 7), v2 = {u2, u5, u7}
        assertArrayEquals(new int[]{3, 3}, c.exactImageSizes(edge(P, 3, A)));
        assertArrayEquals(new int[]{3, 3}, c.exactImageSizes(edge(A, 2, D)));
        // Figure 4: D-1-M has v1 = {u10, u12} (u3 invalid), v2 = {u9, u11}
        assertArrayEquals(new int[]{2, 2}, c.exactImageSizes(edge(D, 1, M)));
        // Figure 3 and the example after Definition 4: S1 has three images per vertex, s_G(S1) = 3
        assertArrayEquals(new int[]{3, 3, 3}, c.exactImageSizes(S1));
        assertEquals(3, c.exactMni(S1));
    }

    @Test
    void withoutWeightLabelsTheExampleIsNotReproduced() {
        // Control: on the unlabeled graph (bottleneck model) u6 IS an image of P in P-A,
        // contradicting Figures 3 and 4; this is why WEGM mines the weight-labeled graph.
        MniSupportCounter c = new MniSupportCounter(figure1(), new Metrics());
        assertArrayEquals(new int[]{4, 3}, c.exactImageSizes(edge(P, 0, A)));
    }

    @Test
    void maxMethodWeightsOfFigure5() {
        assertEquals(3.0, WEGMStrategy.weightMax(S1, 2));
        assertEquals(3.0, WEGMStrategy.weightMax(edge(P, 3, A), 2));
        assertEquals(0.0, WEGMStrategy.weightMax(edge(D, 1, M), 2), "D-1-M fails w = 2 (p. 553)");
    }

    @Test
    void miningAtF2W2PrunesEveryEdgeBelowW() {
        LabeledWeightedGraph g = WEGMStrategy.weightLabeledGraph(figure1());
        Metrics m = new Metrics();
        Set<String> result = new TreeSet<>();
        for (MiningEngine.FrequentPattern fp : new MiningEngine(g, m).mine(2, new WEGMStrategy(2, m))) {
            result.add(CanonicalCode.of(fp.pattern));
            for (int[] e : fp.pattern.edges()) assertTrue(e[2] >= 2, "an edge below w = 2 reached the result");
        }
        // p. 553, Max method at w = 2: P-3-A and A-2-D are kept, D-1-M is pruned, so S1 is
        // generated and S2 (S1 plus D-1-M) is not.
        assertTrue(result.contains(CanonicalCode.of(edge(P, 3, A))));
        assertTrue(result.contains(CanonicalCode.of(edge(A, 2, D))));
        assertTrue(result.contains(CanonicalCode.of(S1)));
        assertFalse(result.contains(CanonicalCode.of(edge(D, 1, M))));
        // Documented discrepancy: undirected MNI also makes P-3-P and A-4-A frequent at f = 2,
        // which the article's list of frequent edges (p. 550) does not show.
        assertTrue(result.contains(CanonicalCode.of(edge(P, 3, P))));
        assertTrue(result.contains(CanonicalCode.of(edge(A, 4, A))));
        assertTrue(m.prunedByEdgeType > 0, "the early pruning must have acted");
    }
}
