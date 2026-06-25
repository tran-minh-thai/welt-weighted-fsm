package welt.core;

/**
 * MULTI-CRITERIA VOTING for choosing the extension point / matching start vertex
 * (Section~\ref{sec:pivot} of the paper) — contribution (ii) of WeLT.
 *
 * <p>For each candidate pattern vertex {@code v} as an extension point:
 * <pre>
 *   score(v) = α·r̂_dom(v) + β·r̂_deg(v) + γ·r̂_w(v)
 * </pre>
 * where (each component NORMALIZED to [0,1], higher = more preferred):
 * <ul>
 *   <li>{@code r̂_dom}: prefers a SMALL candidate DOMAIN (high structural pruning
 *       potential). The raw signal is the domain size — the smaller, the higher the score.</li>
 *   <li>{@code r̂_deg}: prefers a HIGH pattern DEGREE (tighter connectivity constraint).</li>
 *   <li>{@code r̂_w}: prefers the vertex with the TIGHTEST WEIGHT UPPER BOUND around it
 *       (most likely to violate τ_w early). The raw signal is the weight UB — the smaller,
 *       the higher the score. This is the first time a weight signal participates in the
 *       matching-order decision for Weighted FSM.</li>
 * </ul>
 *
 * <p><b>Correctness invariant.</b> The voting rule ONLY changes the traversal order
 * (performance), NOT the result set: accepting a pattern is still decided by the exact
 * {@code MNI_G} and {@code W}. Hence ablation over (α,β,γ) is safe for measuring the
 * contribution of each signal.
 *
 * <p><b>Ablation.</b> Degenerate configurations reproduce classic heuristics:
 * {@code (1,0,0)}=smallest domain (GraMi's default MNI); {@code (0,1,0)}=highest degree;
 * {@code (0,0,1)}=tightest weight. {@link #WELT_DEFAULT} balances all three.
 *
 * <p>This class is PURE: it takes precomputed feature arrays and does not depend on the
 * graph — easy to unit-test. The caller (e.g. {@link MniSupportCounter}) computes the features.
 */
public final class PivotVoter {

    private final double alpha;   // weight of r̂_dom
    private final double beta;    // weight of r̂_deg
    private final double gamma;   // weight of r̂_w

    /** Balances the three signals (the WeLT proposal). */
    public static final PivotVoter WELT_DEFAULT = new PivotVoter(1.0, 1.0, 1.0);
    /** Ablation: smallest domain only (≡ GraMi's default MNI). */
    public static final PivotVoter DOMAIN_ONLY = new PivotVoter(1.0, 0.0, 0.0);
    /** Ablation: highest degree only. */
    public static final PivotVoter DEGREE_ONLY = new PivotVoter(0.0, 1.0, 0.0);
    /** Ablation: tightest weight only. */
    public static final PivotVoter WEIGHT_ONLY = new PivotVoter(0.0, 0.0, 1.0);

    public PivotVoter(double alpha, double beta, double gamma) {
        if (alpha < 0 || beta < 0 || gamma < 0) {
            throw new IllegalArgumentException("Voting weights must be ≥ 0");
        }
        this.alpha = alpha;
        this.beta = beta;
        this.gamma = gamma;
    }

    public double alpha() { return alpha; }
    public double beta()  { return beta; }
    public double gamma() { return gamma; }

    /**
     * Compute the voting scores for all pattern vertices.
     *
     * @param domainSize   candidate domain size (SMALL is good). Use a proxy if the
     *                     domain has not been built yet.
     * @param degree       degree within the pattern (HIGH is good).
     * @param weightUpperBound weight upper bound around the vertex (SMALL is good); pass
     *                     {@code Double.POSITIVE_INFINITY} if there is no weight signal.
     * @return array score[v] ∈ [0, α+β+γ].
     */
    public double[] scores(double[] domainSize, int[] degree, double[] weightUpperBound) {
        int n = domainSize.length;
        if (degree.length != n || weightUpperBound.length != n) {
            throw new IllegalArgumentException("Feature arrays must have the same length");
        }
        // normalize: "goodness" in [0,1], higher = more preferred
        double[] gDom = goodnessSmallBetter(domainSize);
        double[] gDeg = goodnessLargeBetter(toDouble(degree));
        double[] gW = goodnessSmallBetter(weightUpperBound);

        double[] score = new double[n];
        for (int v = 0; v < n; v++) {
            score[v] = alpha * gDom[v] + beta * gDeg[v] + gamma * gW[v];
        }
        return score;
    }

    /**
     * Choose the highest-scoring vertex (tie-break by smaller id, for determinism).
     */
    public int choosePivot(double[] domainSize, int[] degree, double[] weightUpperBound) {
        double[] s = scores(domainSize, degree, weightUpperBound);
        int best = 0;
        for (int v = 1; v < s.length; v++) {
            if (s[v] > s[best] + 1e-12) best = v;
        }
        return best;
    }

    /**
     * Order the pattern vertices by DESCENDING voting score (higher score first),
     * tie-break by ascending id for determinism. Used for the variable order in MNI
     * counting and for the matching order — affects performance only.
     */
    public Integer[] order(double[] domainSize, int[] degree, double[] weightUpperBound) {
        double[] s = scores(domainSize, degree, weightUpperBound);
        int n = s.length;
        Integer[] ord = new Integer[n];
        for (int i = 0; i < n; i++) ord[i] = i;
        java.util.Arrays.sort(ord, (a, b) -> {
            if (Math.abs(s[a] - s[b]) > 1e-12) return Double.compare(s[b], s[a]); // higher score first
            return Integer.compare(a, b);
        });
        return ord;
    }

    /** Goodness when a SMALL VALUE is good: min-max normalize, then invert. Uniform → 0. */
    private static double[] goodnessSmallBetter(double[] raw) {
        int n = raw.length;
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double x : raw) {
            if (x < min) min = x;
            if (Double.isFinite(x) && x > max) max = x;
        }
        // if all values are infinite (no signal) → no distinction
        if (!Double.isFinite(max) || !Double.isFinite(min)) return new double[n];
        double span = max - min;
        double[] g = new double[n];
        if (span <= 0) return g; // uniform → 0 (no one preferred)
        for (int i = 0; i < n; i++) {
            double x = Double.isFinite(raw[i]) ? raw[i] : max; // ∞ treated as the worst
            g[i] = (max - x) / span; // smallest → 1, largest → 0
        }
        return g;
    }

    /** Goodness when a LARGE VALUE is good: min-max normalize. Uniform → 0. */
    private static double[] goodnessLargeBetter(double[] raw) {
        int n = raw.length;
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double x : raw) {
            if (x < min) min = x;
            if (x > max) max = x;
        }
        double span = max - min;
        double[] g = new double[n];
        if (span <= 0) return g;
        for (int i = 0; i < n; i++) g[i] = (raw[i] - min) / span; // largest → 1
        return g;
    }

    private static double[] toDouble(int[] a) {
        double[] d = new double[a.length];
        for (int i = 0; i < a.length; i++) d[i] = a[i];
        return d;
    }
}
