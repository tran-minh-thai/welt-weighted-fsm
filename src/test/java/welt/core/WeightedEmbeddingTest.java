package welt.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the WEIGHT side of {@link MniSupportCounter}: weight-thresholded
 * embedding ({@code embedsWithMinWeight}) and the bottleneck weight {@code W(p)}
 * ({@code maxBottleneckWeight}). Small graphs, with W computable by hand.
 */
class WeightedEmbeddingTest {

    private static final int EL = 0;

    /** Star: center b (label 1) connected to 3 label-0 leaves with ω = 30, 70, 90. */
    private LabeledWeightedGraph star() {
        LabeledWeightedGraph g = new LabeledWeightedGraph(4);
        g.setVertexLabel(0, 1); // b
        g.setVertexLabel(1, 0);
        g.setVertexLabel(2, 0);
        g.setVertexLabel(3, 0);
        g.addUndirectedEdge(0, 1, EL, 30.0);
        g.addUndirectedEdge(0, 2, EL, 70.0);
        g.addUndirectedEdge(0, 3, EL, 90.0);
        return g;
    }

    private double[] weightsDesc() {
        return new double[]{90.0, 70.0, 30.0};
    }

    @Test
    void singleEdgeBottleneckIsMaxEdge() {
        // W(edge 0-1) = max over occurrences of (the edge's own ω) = 90.
        MniSupportCounter c = new MniSupportCounter(star(), new Metrics());
        Pattern edge = Pattern.singleEdge(0, 1, EL);
        assertEquals(90.0, c.maxBottleneckWeight(edge, weightsDesc()), 1e-9);
        assertTrue(c.embedsWithMinWeight(edge, 90.0));
        assertFalse(c.embedsWithMinWeight(edge, 90.0001));
    }

    @Test
    void pathBottleneckIsSecondStrongestPair() {
        // Path 0-1-0: the two arms pick 2 of {30,70,90}; bottleneck = min of the two arms;
        // max over choices = min(70,90)=70.
        MniSupportCounter c = new MniSupportCounter(star(), new Metrics());
        Pattern path = Pattern.of(new int[]{0, 1, 0}, new int[][]{{0, 1, EL}, {1, 2, EL}});
        assertEquals(70.0, c.maxBottleneckWeight(path, weightsDesc()), 1e-9);
        assertTrue(c.embedsWithMinWeight(path, 70.0));
        assertFalse(c.embedsWithMinWeight(path, 71.0));
    }

    @Test
    void noEmbeddingAboveMaxWeight() {
        MniSupportCounter c = new MniSupportCounter(star(), new Metrics());
        Pattern edge = Pattern.singleEdge(0, 1, EL);
        // no edge has ω >= 200 ⇒ not embeddable
        assertFalse(c.embedsWithMinWeight(edge, 200.0));
    }

    @Test
    void weightCheckEqualsGEdgeGraph() {
        // Equivalence check: embedsWithMinWeight(p,w) ⇔ p embeds in G_{>=w}.
        // At threshold 70, only edges 70 and 90 remain ⇒ path 0-1-0 still embeds (2 arms).
        MniSupportCounter c = new MniSupportCounter(star(), new Metrics());
        Pattern path = Pattern.of(new int[]{0, 1, 0}, new int[][]{{0, 1, EL}, {1, 2, EL}});
        assertTrue(c.embedsWithMinWeight(path, 70.0));
        // at threshold 90 only 1 edge remains ⇒ the 2-arm path does NOT embed
        assertFalse(c.embedsWithMinWeight(path, 90.0));
    }
}
