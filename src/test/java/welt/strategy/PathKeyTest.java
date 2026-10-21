package welt.strategy;

import org.junit.jupiter.api.Test;
import welt.core.CanonicalCode;
import welt.core.Pattern;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lookup-table key of a 2-edge path identifies the path up to isomorphism, as its canonical code does. */
class PathKeyTest {

    /** Every path a-b-c with vertex labels in {0,1,2} and edge labels in {0,1}, in both vertex orders. */
    private static List<Pattern> paths() {
        List<Pattern> ps = new ArrayList<>();
        for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) for (int c = 0; c < 3; c++)
            for (int e1 = 0; e1 < 2; e1++) for (int e2 = 0; e2 < 2; e2++) {
                ps.add(Pattern.of(new int[]{a, b, c}, new int[][]{{0, 1, e1}, {1, 2, e2}}));
                // centre first, ends after it: another vertex order of the same path
                ps.add(Pattern.of(new int[]{b, c, a}, new int[][]{{0, 1, e2}, {2, 0, e1}}));
            }
        return ps;
    }

    @Test
    void keyEqualityIsIsomorphism() {
        List<Pattern> ps = paths();
        int pairs = 0, equal = 0;
        for (Pattern p : ps) {
            for (Pattern q : ps) {
                boolean sameKey = WeightedLookupTable.PathKey.of(p).equals(WeightedLookupTable.PathKey.of(q));
                boolean sameCode = CanonicalCode.of(p).equals(CanonicalCode.of(q));
                assertEquals(sameCode, sameKey, p + " vs " + q);
                pairs++;
                if (sameCode) equal++;
            }
        }
        assertEquals(216 * 216, pairs); // 108 paths, each in two vertex orders
        // both outcomes occur: isomorphic pairs beyond the diagonal, and non-isomorphic pairs
        assertTrue(equal > ps.size() && equal < pairs, "isomorphic pairs " + equal + " of " + pairs);
    }

    @Test
    void keyFromLabelsMatchesKeyFromPattern() {
        for (Pattern p : paths()) {
            int[] e1 = p.edges().get(0), e2 = p.edges().get(1);
            int b = (e1[0] == e2[0] || e1[0] == e2[1]) ? e1[0] : e1[1];
            int a = (e1[0] == b) ? e1[1] : e1[0], c = (e2[0] == b) ? e2[1] : e2[0];
            assertEquals(WeightedLookupTable.PathKey.of(p),
                    WeightedLookupTable.PathKey.of(p.nodeLabel(b), p.nodeLabel(a), e1[2], p.nodeLabel(c), e2[2]));
        }
    }

    @Test
    void onlyTwoEdgePaths() {
        assertThrows(IllegalArgumentException.class, () -> WeightedLookupTable.PathKey.of(Pattern.singleEdge(0, 1, 0)));
        Pattern triangle = Pattern.of(new int[]{0, 1, 2}, new int[][]{{0, 1, 0}, {1, 2, 0}, {0, 2, 0}});
        assertThrows(IllegalArgumentException.class, () -> WeightedLookupTable.PathKey.of(triangle));
    }
}
