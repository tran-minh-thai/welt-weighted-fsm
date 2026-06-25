package welt.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PivotVoter}: [0,1] normalization, selection of the
 * highest-scoring vertex, and the ablation configurations that degenerate into
 * single-criterion heuristics.
 */
class PivotVoterTest {

    // 4 hypothetical vertices with sharply contrasting features:
    //   vertex 0: large domain,  low degree,  high UB  (bad on every axis)
    //   vertex 1: small domain,  low degree,  high UB  (good only on domain)
    //   vertex 2: large domain,  high degree, high UB  (good only on degree)
    //   vertex 3: large domain,  low degree,  low UB   (good only on weight)
    private final double[] domain = {100, 5, 100, 100};
    private final int[] degree    = {1, 1, 4, 1};
    private final double[] weight  = {90, 90, 90, 10};

    @Test
    void domainOnlyPicksSmallestDomain() {
        assertEquals(1, PivotVoter.DOMAIN_ONLY.choosePivot(domain, degree, weight));
    }

    @Test
    void degreeOnlyPicksHighestDegree() {
        assertEquals(2, PivotVoter.DEGREE_ONLY.choosePivot(domain, degree, weight));
    }

    @Test
    void weightOnlyPicksTightestUpperBound() {
        assertEquals(3, PivotVoter.WEIGHT_ONLY.choosePivot(domain, degree, weight));
    }

    @Test
    void scoresAreNormalizedWithinSumOfWeights() {
        double[] s = PivotVoter.WELT_DEFAULT.scores(domain, degree, weight);
        double sum = PivotVoter.WELT_DEFAULT.alpha()
                + PivotVoter.WELT_DEFAULT.beta() + PivotVoter.WELT_DEFAULT.gamma();
        for (double x : s) {
            assertTrue(x >= -1e-9 && x <= sum + 1e-9, "score lies within [0, α+β+γ]: " + x);
        }
    }

    @Test
    void uniformFeaturesGiveZeroContribution() {
        // all vertices identical ⇒ no criterion distinguishes them ⇒ pick the smallest id
        double[] dom = {7, 7, 7};
        int[] deg = {2, 2, 2};
        double[] w = {50, 50, 50};
        assertEquals(0, PivotVoter.WELT_DEFAULT.choosePivot(dom, deg, w));
        for (double x : PivotVoter.WELT_DEFAULT.scores(dom, deg, w)) {
            assertEquals(0.0, x, 1e-12);
        }
    }

    @Test
    void infiniteWeightUbMeansNoWeightSignal() {
        // UB = +∞ at every vertex (no weight signal) ⇒ the r̂_w component does not break
        double[] w = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        // then only degree decides (WELT_DEFAULT) ⇒ vertex 2 (highest degree) unless the domain rescues vertex 1
        int pick = PivotVoter.WEIGHT_ONLY.choosePivot(domain, degree, w);
        assertEquals(0, pick, "no weight signal ⇒ WEIGHT_ONLY returns the smallest id");
    }
}
