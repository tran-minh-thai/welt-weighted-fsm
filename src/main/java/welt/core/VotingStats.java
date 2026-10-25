package welt.core;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Counters that locate the work the matching order can change, for the analysis of voting. They
 * are filled by {@link MniSupportCounter} when a stats object is attached and never change the
 * search: each call records the backtracking nodes and embedding searches it caused, the size of
 * the pattern, and which orders a grid of voters would produce on the same signals.
 *
 * <p>Two kinds of call are recorded, each in two phases ({@link #phase}): {@code mni} (one MNI
 * count that reaches the embedding searches) and {@code weight} (one edge-weight check). For an
 * MNI call the order is the variable order; for a weight call it is the start vertex, the only
 * choice voting makes there.
 */
public final class VotingStats {

    public static final int TABLE = 0, MINING = 1;
    private static final String[] PHASE = {"table", "mining"};

    /** Phase the next calls belong to; the harness sets it around the lookup-table build. */
    public int phase = MINING;

    private final List<PivotVoter> grid;

    /** Per phase and kind. */
    public static final class Part {
        public long calls, backtrack, searches;
        public long passCalls, passBacktrack, failCalls, failBacktrack; // weight checks only
        public long pushdownValues, searchedValues;                     // MNI counts only
        public long invariantCalls;   // every voter of the grid gives the same order (MNI) or start (weight)
        public long distinctSum, distinctMax;
        public long constDom, constDeg, constW;
        public long verticesSum, verticesMax, edgesSum, edgesMax;
        /** vertices -> {calls, backtrack, searches}. */
        public final Map<Integer, long[]> bySize = new TreeMap<>();
    }

    private final Part[][] parts = {{new Part(), new Part()}, {new Part(), new Part()}}; // [phase][0 mni, 1 weight]

    public VotingStats(List<PivotVoter> grid) {
        this.grid = grid;
    }

    public Part part(int phase, boolean weight) {
        return parts[phase][weight ? 1 : 0];
    }

    /** Record the grid analysis of one call, before its searches run. */
    void signals(Part pt, Pattern p, double[] dom, int[] deg, double[] w, boolean weight) {
        Set<String> distinct = new HashSet<>();
        for (PivotVoter v : grid) {
            if (weight) {
                distinct.add(Integer.toString(v.choosePivot(dom, deg, w)));
            } else {
                Integer[] o = v.order(dom, deg, w);
                distinct.add(java.util.Arrays.toString(o));
            }
        }
        pt.distinctSum += distinct.size();
        pt.distinctMax = Math.max(pt.distinctMax, distinct.size());
        if (distinct.size() == 1) pt.invariantCalls++;
        if (constant(dom)) pt.constDom++;
        if (constant(toDouble(deg))) pt.constDeg++;
        if (constant(w)) pt.constW++;
    }

    /** Record the cost of one call once its searches are done. */
    void cost(Part pt, Pattern p, long backtrack, long searches) {
        pt.calls++;
        pt.backtrack += backtrack;
        pt.searches += searches;
        int h = p.nodeCount(), r = p.edgeCount();
        pt.verticesSum += h;
        pt.verticesMax = Math.max(pt.verticesMax, h);
        pt.edgesSum += r;
        pt.edgesMax = Math.max(pt.edgesMax, r);
        long[] s = pt.bySize.computeIfAbsent(h, k -> new long[3]);
        s[0]++;
        s[1] += backtrack;
        s[2] += searches;
    }

    private static boolean constant(double[] x) {
        for (double v : x) if (Double.compare(v, x[0]) != 0) return false;
        return true;
    }

    private static double[] toDouble(int[] a) {
        double[] d = new double[a.length];
        for (int i = 0; i < a.length; i++) d[i] = a[i];
        return d;
    }

    /** JSON object of every part, keys {@code <phase>.<kind>.<counter>}. */
    public String json() {
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (int ph = 0; ph < 2; ph++) {
            for (int k = 0; k < 2; k++) {
                Part pt = parts[ph][k];
                String pre = PHASE[ph] + "." + (k == 0 ? "mni" : "weight") + ".";
                long[] vals = {pt.calls, pt.backtrack, pt.searches, pt.passCalls, pt.passBacktrack, pt.failCalls,
                        pt.failBacktrack, pt.pushdownValues, pt.searchedValues, pt.invariantCalls, pt.distinctSum,
                        pt.distinctMax, pt.constDom, pt.constDeg, pt.constW, pt.verticesSum, pt.verticesMax,
                        pt.edgesSum, pt.edgesMax};
                String[] names = {"calls", "backtrack", "searches", "pass_calls", "pass_backtrack", "fail_calls",
                        "fail_backtrack", "pushdown_values", "searched_values", "invariant_calls", "distinct_sum",
                        "distinct_max", "const_dom", "const_deg", "const_w", "vertices_sum", "vertices_max",
                        "edges_sum", "edges_max"};
                for (int i = 0; i < names.length; i++) {
                    b.append(first ? "" : ",").append('"').append(pre).append(names[i]).append("\":").append(vals[i]);
                    first = false;
                }
                b.append(",\"").append(pre).append("by_size\":{");
                boolean f2 = true;
                for (Map.Entry<Integer, long[]> e : pt.bySize.entrySet()) {
                    b.append(f2 ? "" : ",").append(String.format(Locale.ROOT, "\"%d\":[%d,%d,%d]", e.getKey(),
                            e.getValue()[0], e.getValue()[1], e.getValue()[2]));
                    f2 = false;
                }
                b.append('}');
            }
        }
        return b.append('}').toString();
    }
}
