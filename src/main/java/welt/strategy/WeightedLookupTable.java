package welt.strategy;

import welt.core.CanonicalCode;
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
 * Weighted extension lookup table. During preprocessing,
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
 *
 * <p>{@link Options} switch two cost reductions, both off by default:
 * <ul>
 *   <li>{@code keepSupports}: the support results computed here for the 1- and 2-edge
 *       patterns are kept and handed to the miner ({@link #knownSupport}), which then does not
 *       count those patterns again. The domains are mapped to the miner's vertex order through
 *       an isomorphism; any isomorphism is valid, since corresponding vertices have the same
 *       valid images and a stored domain contains every valid image of its vertex.</li>
 *   <li>{@code threshold}: for a run at a single {@code minWeight}, each frequent 2-edge path
 *       stores whether it embeds into G restricted to edges of weight &gt;= minWeight (one weight
 *       check) instead of its exact MaxW (a binary search over the distinct weights). P2 needs
 *       only that comparison, so its decisions are unchanged; the table then answers the weight
 *       check of those paths during mining ({@link #knownWeightOk}). Such a table refuses any
 *       other {@code minWeight}.</li>
 * </ul>
 */
public final class WeightedLookupTable {

    public static final int DEFAULT_K = 2;

    private final int k;
    private final Map<String, Double> maxW = new HashMap<>(); // code -> MaxW(p), frequent patterns only
    /** Heaviest edge of each edge type (GraphIndex triple key): the weight bound of 1-edge patterns. */
    private final Map<Long, Double> maxWByType = new HashMap<>();
    /** Support results of the evaluated 1- and 2-edge patterns, by canonical code (keepSupports). */
    private final Map<String, Stored> supports = new HashMap<>();
    private int supportsMinSup = -1;
    /** minWeight of a threshold table; NaN for a table of exact MaxW values. */
    private double threshold = Double.NaN;

    /** Cost options of the full {@code build}; both off by default. */
    public static final class Options {
        public boolean keepSupports = false;
        /** minWeight of a threshold table; NaN builds exact MaxW values. */
        public double threshold = Double.NaN;
    }

    private static final class Stored {
        final Pattern pattern;
        final MniSupportCounter.SupportResult result;

        Stored(Pattern pattern, MniSupportCounter.SupportResult result) {
            this.pattern = pattern;
            this.result = result;
        }
    }

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
        return build(g, index, minSup, counter, metrics, new Options());
    }

    /** Build with the cost options of {@link Options}. */
    public static WeightedLookupTable build(LabeledWeightedGraph g, GraphIndex index, int minSup,
                                            MniSupportCounter counter, Metrics metrics, Options opt) {
        WeightedLookupTable t = new WeightedLookupTable(DEFAULT_K);
        t.threshold = opt.threshold;
        if (opt.keepSupports) t.supportsMinSup = minSup;
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
            MniSupportCounter.SupportResult r = counter.supportWithDomains(p, minSup, null);
            if (opt.keepSupports) t.supports.put(CanonicalCode.of(p), new Stored(p, r));
            if (r.mni != MniSupportCounter.INFREQUENT) freqEdges.add(p);
        }

        // extend into 2-edge paths, keep frequent patterns, store W(p)
        Set<String> seen2 = new HashSet<>();
        for (Pattern e : freqEdges) {
            for (Pattern child : extendByLeaf(e, index)) {
                String code = WeightedCanonicalCode.of(child);
                if (!seen2.add(code)) continue;
                evaluated++;
                MniSupportCounter.SupportResult r = counter.supportWithDomains(child, minSup, null);
                if (opt.keepSupports) t.supports.putIfAbsent(CanonicalCode.of(child), new Stored(child, r));
                if (r.mni == MniSupportCounter.INFREQUENT) continue;
                double w = Double.isNaN(opt.threshold)
                        ? counter.maxBottleneckWeight(child, index.sortedDistinctWeightsDesc)
                        : (counter.embedsWithMinWeight(child, opt.threshold) ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY);
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
        checkThreshold(minWeight);
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

    private void checkThreshold(double minWeight) {
        if (!Double.isNaN(threshold) && minWeight != threshold) {
            throw new IllegalStateException("threshold table built for minWeight=" + threshold + ", queried at " + minWeight);
        }
    }

    /** minWeight of a threshold table, NaN for exact MaxW values. */
    public double threshold() {
        return threshold;
    }

    /**
     * The support result computed during the build for a pattern isomorphic to {@code p}, with
     * its domains in the vertex order of {@code p}; {@code null} when none was kept, or when the
     * table was built for another minSup.
     */
    public MniSupportCounter.SupportResult knownSupport(Pattern p, String code, int minSup) {
        if (minSup != supportsMinSup) return null;
        Stored st = supports.get(code);
        if (st == null) return null;
        if (st.result.domains == null) return st.result; // INFREQUENT: no domains to map
        int[] phi = isomorphism(p, st.pattern);
        if (phi == null) throw new IllegalStateException("canonical code " + code + " matches a non-isomorphic pattern");
        int[][] mapped = new int[p.nodeCount()][];
        for (int i = 0; i < mapped.length; i++) mapped[i] = st.result.domains[phi[i]];
        return new MniSupportCounter.SupportResult(st.result.mni, mapped);
    }

    /**
     * Answer of the weight check "p embeds into G restricted to edges of weight >= minWeight" for
     * a 2-edge path recorded by a threshold table; {@code null} when not recorded.
     */
    public Boolean knownWeightOk(Pattern p, double minWeight) {
        if (Double.isNaN(threshold) || p.edgeCount() != k) return null;
        checkThreshold(minWeight);
        Double w = maxW.get(WeightedCanonicalCode.of(p));
        return w == null ? null : w >= minWeight;
    }

    /** phi with phi[i] = vertex of q matched to vertex i of p (labels and labelled edges kept), or null. */
    static int[] isomorphism(Pattern p, Pattern q) {
        int n = p.nodeCount();
        if (n != q.nodeCount() || p.edgeCount() != q.edgeCount()) return null;
        int[] phi = new int[n];
        boolean[] used = new boolean[n];
        return extend(p, q, phi, used, 0) ? phi : null;
    }

    private static boolean extend(Pattern p, Pattern q, int[] phi, boolean[] used, int i) {
        int n = p.nodeCount();
        if (i == n) return true;
        for (int j = 0; j < n; j++) {
            if (used[j] || p.nodeLabel(i) != q.nodeLabel(j)) continue;
            boolean ok = true;
            for (int a = 0; a < i && ok; a++) {
                if (p.edgeLabelBetween(i, a) != q.edgeLabelBetween(j, phi[a])) ok = false;
            }
            if (!ok) continue;
            phi[i] = j;
            used[j] = true;
            if (extend(p, q, phi, used, i + 1)) return true;
            used[j] = false;
        }
        return false;
    }
}
