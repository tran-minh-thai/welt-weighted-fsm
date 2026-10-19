package welt.strategy;

import org.junit.jupiter.api.Test;
import welt.core.CanonicalCode;
import welt.core.GraphIndex;
import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.Metrics;
import welt.core.MiningEngine;
import welt.core.MniSupportCounter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GraMiFilter solves WeLT's problem: its result set equals WeLT's, which reaches the answer by a
 * different path (lookup table, weight-gated extension). Its search is GraMi's: no pattern is
 * pruned by weight and every MNI-frequent pattern is extended.
 */
class GraMiFilterTest {

    private static LabeledWeightedGraph graph(String ds) throws IOException {
        return new LgGraphReader().read(Path.of("datasets/" + ds)).graph;
    }

    private static Set<String> codes(List<MiningEngine.FrequentPattern> fps) {
        Set<String> s = new TreeSet<>();
        for (MiningEngine.FrequentPattern fp : fps) s.add(CanonicalCode.of(fp.pattern));
        return s;
    }

    private static Set<String> welt(LabeledWeightedGraph g, int minSup, double minWeight) {
        Metrics m = new Metrics();
        MniSupportCounter c = new MniSupportCounter(g, m);
        WeightedLookupTable table = WeightedLookupTable.build(g, new GraphIndex(g, minSup), minSup, c, m);
        return codes(new MiningEngine(g, m).mine(minSup, new WeLTStrategy(g, minWeight, c, table, m)));
    }

    private void sameAsWeLT(String ds, int minSup, double minWeight) throws IOException {
        LabeledWeightedGraph g = graph(ds);
        Metrics m = new Metrics();
        Set<String> got = codes(new MiningEngine(g, m).mine(minSup,
                new GraMiFilterStrategy(minWeight, new MniSupportCounter(g, m))));
        Set<String> expected = welt(g, minSup, minWeight);
        assertTrue(!expected.isEmpty(), "the cell must have results to compare");
        assertEquals(expected, got, ds + " minSup=" + minSup + " minWeight=" + minWeight);
        assertEquals(got.size(), m.resultCount);
    }

    @Test void citeseer300w0() throws IOException { sameAsWeLT("citeseer.lg", 300, 0); }
    @Test void citeseer300w95() throws IOException { sameAsWeLT("citeseer.lg", 300, 95); }
    @Test void citeseer300w98() throws IOException { sameAsWeLT("citeseer.lg", 300, 98); }
    @Test void email15w336() throws IOException { sameAsWeLT("email_eu.lg", 15, 336); }

    /** Same search as GraMi: same frequent patterns, all extended, none pruned by weight. */
    @Test
    void searchEqualsGraMi() throws IOException {
        LabeledWeightedGraph g = graph("email_eu.lg");
        Metrics mg = new Metrics();
        List<MiningEngine.FrequentPattern> frequent = new MiningEngine(g, mg).mine(15, new GraMiStrategy());
        Metrics mf = new Metrics();
        List<MiningEngine.FrequentPattern> kept = new MiningEngine(g, mf).mine(15,
                new GraMiFilterStrategy(336, new MniSupportCounter(g, mf)));
        assertEquals(mg.extended, mf.extended, "every frequent pattern is extended");
        assertEquals(mg.mniFrequent, mf.mniFrequent, "same frequent patterns");
        assertEquals(0, mf.prunedByStrategy);
        assertTrue(kept.size() < frequent.size(), "the weight threshold removes patterns in this cell: "
                + kept.size() + " of " + frequent.size());
        assertEquals(frequent.size() - kept.size(), mf.mniFrequent - mf.resultCount);
    }
}
