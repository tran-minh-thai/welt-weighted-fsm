package welt.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MniSupportCounter} on small graphs whose MNI can be computed
 * by hand. Edge label 0 is used (unlabeled edges).
 */
class MniSupportCounterTest {

    private static final int EL = 0;

    @Test
    void starGraphEdgeMniIsBottleneck() {
        // Center c (label 1) connected to 3 leaves l1,l2,l3 (label 0). Edge pattern (0-1).
        // Images of the label-0 variable = {l1,l2,l3}=3 ; images of the label-1 variable = {c}=1 ; MNI=min=1.
        LabeledWeightedGraph g = new LabeledWeightedGraph(4);
        g.setVertexLabel(0, 1); // c
        g.setVertexLabel(1, 0);
        g.setVertexLabel(2, 0);
        g.setVertexLabel(3, 0);
        g.addUndirectedEdge(0, 1, EL, 1.0);
        g.addUndirectedEdge(0, 2, EL, 1.0);
        g.addUndirectedEdge(0, 3, EL, 1.0);

        Metrics m = new Metrics();
        MniSupportCounter c = new MniSupportCounter(g, m);
        Pattern edge = Pattern.singleEdge(0, 1, EL);

        assertEquals(1, c.support(edge, 1), "MNI of the star edge = 1 (bottleneck at the center)");
        assertEquals(MniSupportCounter.INFREQUENT, c.support(edge, 2),
                "minSup=2 ⇒ infrequent because the center variable has only 1 image");
    }

    @Test
    void twoDisjointEdgesMniIsTwo() {
        // (0:l0)-(1:l1) and (2:l0)-(3:l1). Edge pattern 0-1: each variable has 2 images ⇒ MNI=2.
        LabeledWeightedGraph g = new LabeledWeightedGraph(4);
        g.setVertexLabel(0, 0);
        g.setVertexLabel(1, 1);
        g.setVertexLabel(2, 0);
        g.setVertexLabel(3, 1);
        g.addUndirectedEdge(0, 1, EL, 1.0);
        g.addUndirectedEdge(2, 3, EL, 1.0);

        Metrics m = new Metrics();
        MniSupportCounter c = new MniSupportCounter(g, m);
        Pattern edge = Pattern.singleEdge(0, 1, EL);

        assertEquals(2, c.support(edge, 2));
        assertEquals(MniSupportCounter.INFREQUENT, c.support(edge, 3));
    }

    @Test
    void triangleAllSameLabelMni() {
        // Triangle of 3 vertices all with label 0. Edge pattern 0-0: every vertex participates ⇒ images=3 ⇒ MNI=3.
        LabeledWeightedGraph g = new LabeledWeightedGraph(3);
        for (int i = 0; i < 3; i++) g.setVertexLabel(i, 0);
        g.addUndirectedEdge(0, 1, EL, 1.0);
        g.addUndirectedEdge(1, 2, EL, 1.0);
        g.addUndirectedEdge(0, 2, EL, 1.0);

        Metrics m = new Metrics();
        MniSupportCounter c = new MniSupportCounter(g, m);
        assertEquals(3, c.support(Pattern.singleEdge(0, 0, EL), 3));
        // the triangle (3-edge pattern) also has MNI=3
        Pattern tri = Pattern.of(new int[]{0, 0, 0},
                new int[][]{{0, 1, EL}, {1, 2, EL}, {0, 2, EL}});
        assertEquals(3, c.support(tri, 3));
        assertTrue(m.isoCallCount > 0, "there must be at least one embedding-search call");
    }

    @Test
    void pathPatternNotSatisfiedBecomesInfrequent() {
        // A single edge 0-1. Path pattern 0-1-0 (requires a label-1 vertex with 2 label-0 neighbors) ⇒ not embeddable.
        LabeledWeightedGraph g = new LabeledWeightedGraph(2);
        g.setVertexLabel(0, 0);
        g.setVertexLabel(1, 1);
        g.addUndirectedEdge(0, 1, EL, 1.0);

        Metrics m = new Metrics();
        MniSupportCounter c = new MniSupportCounter(g, m);
        Pattern path = Pattern.of(new int[]{0, 1, 0}, new int[][]{{0, 1, EL}, {1, 2, EL}});
        assertEquals(MniSupportCounter.INFREQUENT, c.support(path, 1));
    }
}
