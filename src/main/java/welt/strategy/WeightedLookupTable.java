package welt.strategy;

import welt.core.GraphIndex;
import welt.core.LabeledWeightedGraph;
import welt.core.Metrics;
import welt.core.MniSupportCounter;
import welt.core.Pattern;
import welt.core.WeightedCanonicalCode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Weighted extension lookup table (Section 4.4 of the paper). During preprocessing,
 * compute the set {@code F_k} of all connected FREQUENT {@code k}-edge patterns
 * (default k=2 ⇒ a 2-edge subgraph = a 3-vertex path) and store {@code MaxW(p)=W(p)}
 * for each, indexed by {@link WeightedCanonicalCode}.
 *
 * <p>Serves the DOUBLE FILTER before the (expensive) MNI count:
 * <ul>
 *   <li><b>P1</b> (structural filter): if some k-edge subpattern of S is not in the
 *       table (not frequent) ⇒ S is not frequent ⇒ prune.</li>
 *   <li><b>P2</b> (weight filter): {@code UB_k(S)=min MaxW(p)} &lt; minWeight ⇒ every
 *       extension of S has W &lt; minWeight ⇒ prune (dominance corollary + anti-monotonicity).</li>
 * </ul>
 *
 * <p>The table is computed once per {@code minSup} (independent of minWeight, since it
 * stores the continuous {@code MaxW}), so it is reusable when sweeping minWeight.
 */
public final class WeightedLookupTable {

    public static final int DEFAULT_K = 2;

    private final int k;
    private final Map<String, Double> maxW = new HashMap<>(); // code -> MaxW(p), frequent patterns only

    private WeightedLookupTable(int k) {
        this.k = k;
    }

    public int k() {
        return k;
    }

    public int size() {
        return maxW.size();
    }

    /**
     * Build the k=2 lookup table: enumerate frequent single edges → extend into 2-edge
     * paths → keep frequent patterns (MNI≥minSup) → store W(p). SHARES the same
     * {@code counter}/{@code metrics}, so iso-calls during table construction are also
     * counted in the total (fair).
     */
    public static WeightedLookupTable build(LabeledWeightedGraph g, GraphIndex index, int minSup,
                                            MniSupportCounter counter, Metrics metrics) {
        WeightedLookupTable t = new WeightedLookupTable(DEFAULT_K);
        long isoBefore = metrics.isoCallCount;
        long mniBefore = metrics.mniIsoCalls;

        // frequent single-edge patterns
        List<Pattern> freqEdges = new ArrayList<>();
        Set<String> seen1 = new HashSet<>();
        for (long key : index.presentTriples) {
            int lo = (int) ((key >> 42) & 0x1FFFFF);
            int hi = (int) ((key >> 21) & 0x1FFFFF);
            int el = (int) (key & 0x1FFFFF);
            if (!index.frequentLabels.contains(lo) || !index.frequentLabels.contains(hi)) continue;
            Pattern p = Pattern.singleEdge(lo, hi, el);
            if (!seen1.add(WeightedCanonicalCode.of(p))) continue;
            if (counter.support(p, minSup) != MniSupportCounter.INFREQUENT) freqEdges.add(p);
        }

        // extend into 2-edge paths, keep frequent patterns, store W(p)
        Set<String> seen2 = new HashSet<>();
        for (Pattern e : freqEdges) {
            for (Pattern child : extendByLeaf(e, index)) {
                String code = WeightedCanonicalCode.of(child);
                if (!seen2.add(code)) continue;
                if (counter.support(child, minSup) == MniSupportCounter.INFREQUENT) continue;
                double w = counter.maxBottleneckWeight(child, index.sortedDistinctWeightsDesc);
                t.maxW.put(code, w);
            }
        }

        metrics.tableBuildIsoCalls += (metrics.isoCallCount - isoBefore);
        metrics.tableBuildMniIsoCalls += (metrics.mniIsoCalls - mniBefore);
        return t;
    }

    /** Extend a pattern (here a single edge) by adding a leaf — generates a 2-edge path. */
    private static List<Pattern> extendByLeaf(Pattern p, GraphIndex index) {
        List<Pattern> children = new ArrayList<>();
        int n = p.nodeCount();
        for (int u = 0; u < n; u++) {
            int lu = p.nodeLabel(u);
            for (int lb : index.frequentLabels) {
                for (int el : index.edgeLabels) {
                    if (!index.triplePresent(lu, lb, el)) continue;
                    children.add(p.withLeaf(u, lb, el));
                }
            }
        }
        return children;
    }

    /**
     * Apply the double filter to candidate S. Returns {@code true} if PRUNED (P1 or P2).
     * Applied only when {@code |E(S)| ≥ k}. Updates the pruning counters.
     */
    public boolean prune(Pattern s, double minWeight, Metrics metrics) {
        if (s.edgeCount() < k) return false; // UB_k = +∞, no k-edge subpattern exists

        double ub = Double.POSITIVE_INFINITY;
        for (Pattern sub : connectedTwoEdgeSubpatterns(s)) {
            String code = WeightedCanonicalCode.of(sub);
            Double w = maxW.get(code);
            if (w == null) {            // P1: k-edge subpattern not frequent
                metrics.prunedByP1++;
                metrics.prunedByStrategy++;
                return true;
            }
            if (w < ub) ub = w;
        }
        if (ub < minWeight) {           // P2: UB_k(S) < minWeight
            metrics.prunedByP2++;
            metrics.prunedByStrategy++;
            return true;
        }
        return false;
    }

    /**
     * Enumerate every CONNECTED 2-edge subpattern of S = every pair of adjacent edges
     * (sharing one vertex) ⇒ a 3-vertex path a-b-c.
     */
    private List<Pattern> connectedTwoEdgeSubpatterns(Pattern s) {
        List<int[]> edges = s.edges(); // {u,v,el}
        List<Pattern> subs = new ArrayList<>();
        for (int i = 0; i < edges.size(); i++) {
            for (int j = i + 1; j < edges.size(); j++) {
                int[] e1 = edges.get(i), e2 = edges.get(j);
                int shared = sharedVertex(e1, e2);
                if (shared < 0) continue; // not adjacent ⇒ not connected
                int a = (e1[0] == shared) ? e1[1] : e1[0];
                int c = (e2[0] == shared) ? e2[1] : e2[0];
                // path a-b-c: vertex labels and the two edge labels
                int[] labels = {s.nodeLabel(a), s.nodeLabel(shared), s.nodeLabel(c)};
                int[][] es = {{0, 1, e1[2]}, {1, 2, e2[2]}};
                subs.add(Pattern.of(labels, es));
            }
        }
        return subs;
    }

    private static int sharedVertex(int[] e1, int[] e2) {
        if (e1[0] == e2[0] || e1[0] == e2[1]) return e1[0];
        if (e1[1] == e2[0] || e1[1] == e2[1]) return e1[1];
        return -1;
    }
}
