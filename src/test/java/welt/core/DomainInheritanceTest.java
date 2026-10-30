package welt.core;

import org.junit.jupiter.api.Test;
import welt.strategy.GraMiStrategy;
import welt.strategy.MiningStrategy;
import welt.strategy.WeLTStrategy;
import welt.strategy.WeightedLookupTable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inheriting the parent's reduced domains never loses an image of the child. A child is counted
 * from the domains of its parent, which MNICount returns after the label, degree, neighbour and
 * arc-consistency filters and before its search, so values left unexamined by early stopping stay
 * in them. Mining with inheritance on and off must give the same result set, on cells whose result
 * holds patterns of two or more edges, so that inherited domains are used.
 */
class DomainInheritanceTest {

    interface Factory {
        MiningStrategy make(LabeledWeightedGraph g, int minSup, MniSupportCounter c, Metrics m);
    }

    private static final Factory GRAMI = (g, minSup, c, m) -> new GraMiStrategy();

    private static Factory welt(double minWeight) {
        return (g, minSup, c, m) -> new WeLTStrategy(g, minWeight, c,
                WeightedLookupTable.build(g, new GraphIndex(g, minSup), minSup, c, m), m);
    }

    /** Canonical code -> number of edges of each result pattern. */
    private static Map<String, Integer> mine(LabeledWeightedGraph g, int minSup, boolean inherit, Factory f) {
        Metrics m = new Metrics();
        MniSupportCounter c = new MniSupportCounter(g, m);
        MiningEngine engine = new MiningEngine(g, m);
        engine.setDomainInheritance(inherit);
        List<MiningEngine.FrequentPattern> fps = engine.mine(minSup, f.make(g, minSup, c, m));
        Map<String, Integer> out = new TreeMap<>();
        for (MiningEngine.FrequentPattern fp : fps) {
            assertTrue(fp.support >= minSup, "an accepted pattern has MNI >= minSup");
            out.put(CanonicalCode.of(fp.pattern), fp.pattern.edgeCount());
        }
        return out;
    }

    private void same(String ds, int minSup, Factory f, int minLarger) throws IOException {
        LabeledWeightedGraph g = new LgGraphReader().read(Path.of("datasets/" + ds)).graph;
        Map<String, Integer> on = mine(g, minSup, true, f);
        Map<String, Integer> off = mine(g, minSup, false, f);
        long larger = off.values().stream().filter(e -> e >= 2).count();
        assertTrue(larger >= minLarger, ds + " " + minSup + ": only " + larger
                + " result patterns with two or more edges; the comparison would not exercise inheritance");
        assertEquals(off.keySet(), on.keySet(), ds + " minSup=" + minSup + ": inheritance changed the result set");
    }

    @Test void citeseer300GraMi() throws IOException { same("citeseer.lg", 300, GRAMI, 4); }
    @Test void email12GraMi() throws IOException { same("email_eu.lg", 12, GRAMI, 281); }
    @Test void email10WeLT() throws IOException { same("email_eu.lg", 10, welt(336), 19); }
    @Test void lastfm600WeLT() throws IOException { same("lastfm.lg", 600, welt(40), 11); }
}
