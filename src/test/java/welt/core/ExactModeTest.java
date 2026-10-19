package welt.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact versus approximate mode of {@link MniSupportCounter}. The search budget may only
 * truncate MNI embedding searches; weight checks and the bottleneck binary search must stay
 * exact whatever budget is set, and every truncated MNI search must be counted.
 */
class ExactModeTest {

    private static final int EL = 0;

    /** Path a(0) - b(1) - c(0) with edge weights 5 and 2, plus a second edge a2(0) - b2(1) of weight 9. */
    private static LabeledWeightedGraph graph() {
        LabeledWeightedGraph g = new LabeledWeightedGraph(5);
        g.setVertexLabel(0, 0);
        g.setVertexLabel(1, 1);
        g.setVertexLabel(2, 0);
        g.setVertexLabel(3, 0);
        g.setVertexLabel(4, 1);
        g.addUndirectedEdge(0, 1, EL, 5.0);
        g.addUndirectedEdge(1, 2, EL, 2.0);
        g.addUndirectedEdge(3, 4, EL, 9.0);
        return g;
    }

    @Test
    void defaultCounterIsExact() {
        assertTrue(new MniSupportCounter(graph(), new Metrics()).isExact());
    }

    @Test
    void weightCheckIgnoresBudget() {
        // A single-edge embedding needs 2 backtrack nodes, so a budget of 1 would abort it
        // if the budget applied to weight checks.
        Metrics m = new Metrics();
        MniSupportCounter c = new MniSupportCounter(graph(), m);
        c.setSearchBudget(1);
        assertTrue(c.embedsWithMinWeight(Pattern.singleEdge(0, 1, EL), 9.0));
        Pattern path = Pattern.of(new int[]{0, 1, 0}, new int[][]{{0, 1, EL}, {1, 2, EL}});
        assertTrue(c.embedsWithMinWeight(path, 2.0));
        assertEquals(0, m.budgetExceeded, "weight checks never consume the budget");
    }

    @Test
    void bottleneckIsExactUnderBudget() {
        GraphIndex index = new GraphIndex(graph(), 1);
        MniSupportCounter exact = new MniSupportCounter(graph(), new Metrics());
        MniSupportCounter budgeted = new MniSupportCounter(graph(), new Metrics());
        budgeted.setSearchBudget(1);
        Pattern path = Pattern.of(new int[]{0, 1, 0}, new int[][]{{0, 1, EL}, {1, 2, EL}});
        Pattern edge = Pattern.singleEdge(0, 1, EL);
        assertEquals(2.0, exact.maxBottleneckWeight(path, index.sortedDistinctWeightsDesc));
        assertEquals(9.0, exact.maxBottleneckWeight(edge, index.sortedDistinctWeightsDesc));
        assertEquals(exact.maxBottleneckWeight(path, index.sortedDistinctWeightsDesc),
                budgeted.maxBottleneckWeight(path, index.sortedDistinctWeightsDesc));
        assertEquals(exact.maxBottleneckWeight(edge, index.sortedDistinctWeightsDesc),
                budgeted.maxBottleneckWeight(edge, index.sortedDistinctWeightsDesc));
    }

    @Test
    void mniBudgetAbortsAreCounted() {
        // Exact: the edge pattern (0)-(1) has MNI 2. With budget 1 every MNI search is
        // aborted, MNI becomes a lower bound (infrequent here) and each abort is counted.
        Metrics exactMetrics = new Metrics();
        assertEquals(2, new MniSupportCounter(graph(), exactMetrics).support(Pattern.singleEdge(0, 1, EL), 2));
        assertEquals(0, exactMetrics.budgetExceeded);

        Metrics m = new Metrics();
        MniSupportCounter c = new MniSupportCounter(graph(), m);
        c.setSearchBudget(1);
        assertTrue(!c.isExact());
        assertEquals(MniSupportCounter.INFREQUENT, c.support(Pattern.singleEdge(0, 1, EL), 2));
        assertTrue(m.budgetExceeded > 0, "aborted MNI searches must be counted, got " + m.budgetExceeded);
    }
}
