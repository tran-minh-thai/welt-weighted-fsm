package welt.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Unit tests for {@link CanonicalCode}: invariance under vertex ordering, and
 * distinction when structure or labels differ.
 */
class CanonicalCodeTest {

    @Test
    void sameGraphDifferentVertexOrderHasSameCode() {
        // path with labels 0-1-2: edges (0,1),(1,2)
        Pattern a = Pattern.of(new int[]{0, 1, 2}, new int[][]{{0, 1, 0}, {1, 2, 0}});
        // same graph but with the vertices numbered in reverse: labels 2,1,0 with edges (0,1),(1,2)
        Pattern b = Pattern.of(new int[]{2, 1, 0}, new int[][]{{0, 1, 0}, {1, 2, 0}});
        assertEquals(CanonicalCode.of(a), CanonicalCode.of(b),
                "Two numberings of the same path must yield the same canonical code");
    }

    @Test
    void differentNodeLabelsDifferentCode() {
        Pattern a = Pattern.singleEdge(0, 0, 0);
        Pattern b = Pattern.singleEdge(0, 1, 0);
        assertNotEquals(CanonicalCode.of(a), CanonicalCode.of(b));
    }

    @Test
    void differentEdgeLabelsDifferentCode() {
        Pattern a = Pattern.singleEdge(0, 1, 0);
        Pattern b = Pattern.singleEdge(0, 1, 7);
        assertNotEquals(CanonicalCode.of(a), CanonicalCode.of(b));
    }

    @Test
    void pathVsTriangleDifferentCode() {
        Pattern path = Pattern.of(new int[]{0, 0, 0}, new int[][]{{0, 1, 0}, {1, 2, 0}});
        Pattern triangle = Pattern.of(new int[]{0, 0, 0}, new int[][]{{0, 1, 0}, {1, 2, 0}, {0, 2, 0}});
        assertNotEquals(CanonicalCode.of(path), CanonicalCode.of(triangle));
    }

    @Test
    void triangleVertexPermutationsStable() {
        Pattern t1 = Pattern.of(new int[]{0, 1, 2}, new int[][]{{0, 1, 0}, {1, 2, 0}, {0, 2, 0}});
        Pattern t2 = Pattern.of(new int[]{1, 2, 0}, new int[][]{{0, 1, 0}, {1, 2, 0}, {0, 2, 0}});
        assertEquals(CanonicalCode.of(t1), CanonicalCode.of(t2));
    }
}
