package welt.runner;

import org.junit.jupiter.api.Test;
import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.Metrics;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Accounting identities of the search-space counters on completed runs. Every generated
 * pattern is either pre-pruned by the strategy, pruned by the anti-monotone closure (P3) or
 * MNI-counted; the cells are chosen so that every term is non-zero for some algorithm.
 */
class SearchSpaceCountersTest {

    private static void checkCell(String file, int minSup, double minWeight) throws IOException {
        LabeledWeightedGraph g = new LgGraphReader().read(Path.of(file)).graph;
        long sumP1 = 0, sumP2 = 0, sumEdgeType = 0, sumP3 = 0, sumGap = 0;
        for (String algo : new String[]{"GraMi", "EdgeTypeBound", "EdgeTypeBound-ungated", "WEGM", "WeLT", "OWGraMi"}) {
            RunHarness.Result r = RunHarness.runOnce(g, new RunHarness.Config(algo, minSup, minWeight));
            Metrics m = r.m;
            String at = algo + " " + file + " minSup=" + minSup;
            assertEquals("OK", r.status, at);
            assertEquals(m.prunedByStrategy + m.prunedByApriori + m.candidateCount, m.generated,
                    at + ": N = pre-pruned + P3-pruned + M");
            assertEquals(m.prunedByP1 + m.prunedByP2 + m.prunedByEdgeType, m.prunedByStrategy,
                    at + ": strategy prunes = P1 + P2 + edge type");
            assertTrue(m.candidateCount >= m.mniFrequent && m.mniFrequent >= m.resultCount, at + ": M >= M' >= R");
            assertTrue(m.prunedByP2Single <= m.prunedByP2, at + ": the k = 1 part is included in P2");
            assertTrue(m.mniFrequent >= m.extended && m.extended >= m.frontierPeak, at + ": M' >= extended >= frontier peak");
            assertEquals(m.mniFrequent, m.frequentMemoSize, at + ": the P3 memo holds every MNI-frequent pattern");
            assertEquals(r.patterns.size(), m.resultCount, at);
            if (algo.equals("GraMi")) assertEquals(0, m.prunedByStrategy, at);
            if (!algo.equals("WeLT")) assertEquals(0, m.prunedByP1 + m.prunedByP2, at + ": P1/P2 belong to the WeLT table");
            if (algo.equals("WeLT") || algo.equals("GraMi")) assertEquals(0, m.prunedByEdgeType, at);
            sumP1 += m.prunedByP1;
            sumP2 += m.prunedByP2;
            sumEdgeType += m.prunedByEdgeType;
            sumP3 += m.prunedByApriori;
            sumGap += m.mniFrequent - m.resultCount;
        }
        // Not vacuous: each counter takes part in an identity with a non-zero value.
        assertTrue(sumP1 > 0 && sumP2 > 0 && sumEdgeType > 0 && sumP3 > 0 && sumGap > 0,
                "a counter stayed at zero on " + file + ": P1=" + sumP1 + " P2=" + sumP2
                        + " edgeType=" + sumEdgeType + " P3=" + sumP3 + " M'-R=" + sumGap);
    }

    @Test
    void citeseer300() throws IOException {
        checkCell("datasets/citeseer.lg", 300, 96);
    }

    @Test
    void emailEu15() throws IOException {
        checkCell("datasets/email_eu.lg", 15, 336);
    }
}
