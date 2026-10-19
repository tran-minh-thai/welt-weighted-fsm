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
 * (default k=2: each entry is a 3-vertex path) and store {@code MaxW(p)=W(p)}
 * for each, indexed by {@link WeightedCanonicalCode}. {@code MaxW(p)} is computed
 * exactly via binary search over the distinct edge-weight set of G
 * ({@link MniSupportCounter#maxBottleneckWeight}).
 *
 * <p>Serves the double filter before the (expensive) MNI count:
 * <ul>
 *   <li><b>P1</b> (structural filter): if some k-edge subpattern of S is not in the
 *       table (not frequent) then S is not frequent and the whole subtree is pruned.</li>
 *   <li><b>P2</b> (weight filter): {@code UB_k(S) = min MaxW(p)} &lt; minWeight implies
 *       every extension of S has W &lt; minWeight (dominance corollary + anti-monotonicity),
 *       so the whole subtree is pruned.</li>
 * </ul>
 *
 * <p>The table is built once per {@code minSup} (independent of {@code minWeight},
 * since it stores the continuous {@code MaxW}), so it is reusable when sweeping
 * {@code minWeight}.
 */
public final class WeightedLookupTable {

    public static final int DEFAULT_K = 2;

    private final int k;
    private final Map<String, Double> maxW = new HashMap<>(); // code -> MaxW(p), frequent patterns only
    /** Heaviest edge of each edge type (GraphIndex triple key): the weight bound of 1-edge patterns. */
    private final Map<Long, Double> maxWByType = new HashMap<>();

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
        long weightBefore = metrics.weightIsoCalls;
        long evaluated = 0;

        // heaviest edge per edge type: W of a 1-edge pattern is the weight of one of its
        // occurrences, so it cannot exceed this value (the P2 bound for k = 1)
        for (int u = 0; u < g.numVertices(); u++) {
            for (LabeledWeightedGraph.Adjacency a : g.neighbors(u)) {
                if (a.to <= u) continue;
                long key = GraphIndex.tripleKey(g.vertexLabel(u), g.vertexLabel(a.to), a.edgeLabel);
                t.maxWByType.merge(key, a.weight, Math::max);
            }
        }

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
            evaluated++;
            if (counter.support(p, minSup) != MniSupportCounter.INFREQUENT) freqEdges.add(p);
        }

        // extend into 2-edge paths, keep frequent patterns, store W(p)
        Set<String> seen2 = new HashSet<>();
        for (Pattern e : freqEdges) {
            for (Pattern child : extendByLeaf(e, index)) {
                String code = WeightedCanonicalCode.of(child);
                if (!seen2.add(code)) continue;
                evaluated++;
                if (counter.support(child, minSup) == MniSupportCounter.INFREQUENT) continue;
                double w = counter.maxBottleneckWeight(child, index.sortedDistinctWeightsDesc);
                t.maxW.put(code, w);
            }
        }

        metrics.tableBuildIsoCalls += (metrics.isoCallCount - isoBefore);
        metrics.tableBuildMniIsoCalls += (metrics.mniIsoCalls - mniBefore);
        metrics.tableBuildWeightIsoCalls += (metrics.weightIsoCalls - weightBefore);
        metrics.tableBuildCandidates += evaluated;
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
        return prune(s, minWeight, metrics, true, true);
    }

    /**
     * Ablation form: {@code p1} and {@code p2} switch the two filters independently. With P1
     * off, a k-edge subpattern missing from the table is SKIPPED when computing UB_k (it does
     * not prune), so P2 then uses only the subpatterns present in the table.
     */
    public boolean prune(Pattern s, double minWeight, Metrics metrics, boolean p1, boolean p2) {
        return prune(s, minWeight, metrics, p1, p2, true);
    }

    /**
     * Full form. {@code p2single} switches the k = 1 part of P2: a 1-edge pattern whose edge
     * type has no edge of weight ≥ minWeight cannot reach the threshold (UB_1 = heaviest edge
     * of the type). It acts only when {@code p2} is on. For k ≥ 2 the 2-edge entries already
     * bound every edge (MaxW of a path ≤ the heaviest edge of each of its types).
     */
    public boolean prune(Pattern s, double minWeight, Metrics metrics, boolean p1, boolean p2, boolean p2single) {
        if (!p1 && !p2) return false;
        if (s.edgeCount() == 1) {
            if (!p2 || !p2single) return false;
            int[] e = s.edges().get(0);
            Double w = maxWByType.get(GraphIndex.tripleKey(s.nodeLabel(e[0]), s.nodeLabel(e[1]), e[2]));
            if (w == null || w < minWeight) { // P2, k = 1: UB_1(S) < minWeight
                metrics.prunedByP2++;
                metrics.prunedByP2Single++;
                metrics.prunedByStrategy++;
                return true;
            }
            return false;
        }
        if (s.edgeCount() < k) return false; // UB_k = +∞, no k-edge subpattern exists

        double ub = Double.POSITIVE_INFINITY;
        for (Pattern sub : connectedTwoEdgeSubpatterns(s)) {
            String code = WeightedCanonicalCode.of(sub);
            Double w = maxW.get(code);
            if (w == null) {            // P1: k-edge subpattern not frequent
                if (!p1) continue;      // P1 off: skip this subpattern in UB_k
                metrics.prunedByP1++;
                metrics.prunedByStrategy++;
                return true;
            }
            if (w < ub) ub = w;
        }
        if (p2 && ub < minWeight) {     // P2: UB_k(S) < minWeight
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
