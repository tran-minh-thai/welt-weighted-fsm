package welt.strategy;

import org.junit.jupiter.api.Test;
import welt.core.GraphIndex;
import welt.core.LabeledWeightedGraph;
import welt.core.Metrics;
import welt.core.MniSupportCounter;
import welt.core.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 and P2 of the lookup table switched independently. Graph (labels A=0 B=1 C=2 D=3):
 * a path A-B-C with both edge weights 1, plus a separate edge C-D of weight 9. The 2-edge path
 * A-B-C is in the table with MaxW = 1; B-C-D does not occur, so it is not in the table.
 * The query S = A-B-C-D (3 edges) contains both paths.
 */
class LookupFilterIndependenceTest {

    private static final Pattern S = Pattern.of(new int[]{0, 1, 2, 3},
            new int[][]{{0, 1, 0}, {1, 2, 0}, {2, 3, 0}});

    private static WeightedLookupTable table() {
        LabeledWeightedGraph g = new LabeledWeightedGraph(5);
        g.setVertexLabel(0, 0);
        g.setVertexLabel(1, 1);
        g.setVertexLabel(2, 2);
        g.setVertexLabel(3, 2);
        g.setVertexLabel(4, 3);
        g.addUndirectedEdge(0, 1, 0, 1.0);
        g.addUndirectedEdge(1, 2, 0, 1.0);
        g.addUndirectedEdge(3, 4, 0, 9.0);
        Metrics m = new Metrics();
        return WeightedLookupTable.build(g, new GraphIndex(g, 1), 1, new MniSupportCounter(g, m), m);
    }

    @Test
    void p1PrunesOnTheMissingPath() {
        Metrics m = new Metrics();
        assertTrue(table().prune(S, 5.0, m, true, false));
        assertEquals(1, m.prunedByP1);
        assertEquals(0, m.prunedByP2);
    }

    @Test
    void withP1OffTheMissingPathIsSkippedAndP2StillBounds() {
        // UB_2(S) over the paths present in the table = MaxW(A-B-C) = 1 < 5
        Metrics m = new Metrics();
        assertTrue(table().prune(S, 5.0, m, false, true), "P2 must bound with the present subpatterns");
        assertEquals(0, m.prunedByP1);
        assertEquals(1, m.prunedByP2);
    }

    @Test
    void p2DoesNotPruneWhenThePresentBoundIsHighEnough() {
        Metrics m = new Metrics();
        assertFalse(table().prune(S, 1.0, m, false, true), "UB = 1 is not below tau_w = 1");
        assertFalse(table().prune(S, 5.0, m, false, false), "both filters off");
        assertEquals(0, m.prunedByP1 + m.prunedByP2);
    }
}
