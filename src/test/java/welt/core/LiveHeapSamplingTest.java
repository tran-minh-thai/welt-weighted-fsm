package welt.core;

import org.junit.jupiter.api.Test;
import welt.runner.RunHarness;
import welt.strategy.MiningStrategy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The live-heap run: off by default, leaves the search unchanged, and sees memory the search keeps. */
class LiveHeapSamplingTest {

    private static LabeledWeightedGraph emailEu() throws IOException {
        return new LgGraphReader().read(Path.of("datasets/email_eu.lg")).graph;
    }

    private static long[] search(RunHarness.Result r) {
        return new long[]{r.m.generated, r.m.candidateCount, r.m.mniFrequent, r.m.resultCount, r.m.extended,
                r.m.mniIsoCalls, r.m.weightIsoCalls, r.m.backtrackNodes, r.m.visitedCodeChars, r.m.memoCodeChars,
                r.m.frontierDomainIntsPeak, r.m.tableDomainInts};
    }

    @Test
    void offByDefaultAndSearchUnchanged() throws IOException {
        LabeledWeightedGraph g = emailEu();
        for (String algo : List.of("WeLT", "EdgeTypeBound")) {
            RunHarness.Config off = new RunHarness.Config(algo, 12, 336), on = new RunHarness.Config(algo, 12, 336);
            on.liveHeap = true;
            assertTrue(!off.liveHeap, "live heap is off by default");
            RunHarness.Result a = RunHarness.runOnce(g, off), b = RunHarness.runOnce(g, on);
            assertEquals(0, a.m.liveHeapSamples);
            assertTrue(Double.isNaN(a.m.liveHeapPeakMB));
            assertArrayEquals(search(a), search(b), algo + ": the samples change the search");
            assertTrue(a.m.liveHeapGraphMB > 0 && b.m.liveHeapGraphMB > 0);
        }
    }

    /**
     * Samples follow the 5% rule: at most two plus the number of 5% steps up to the largest retained
     * value, and at least the number of 10% steps from 10000 units to it.
     */
    @Test
    void samplesFollowTheGrowthRule() throws IOException {
        RunHarness.Config c = new RunHarness.Config("EdgeTypeBound", 10, 336);
        c.liveHeap = true;
        RunHarness.Result r = RunHarness.runOnce(emailEu(), c);
        long max = r.m.visitedCodeChars + r.m.memoCodeChars + r.m.frontierDomainIntsPeak + r.m.tableDomainInts;
        long bound = 2 + (long) Math.ceil(Math.log(max) / Math.log(1.05)) + 1;
        // each triggered sample sees more than 1.05 times the previous one, and after the last one the
        // retained data stays within 1.05 times it; from 10000 units on, one step adds well under 5%, so
        // the samples grow by at most 1.10 each
        long lower = (long) Math.floor(Math.log(max / (1.05 * Math.max(r.m.tableDomainInts, 10000))) / Math.log(1.10));
        assertTrue(lower >= 10, "the cell is too small to test the rule: lower bound " + lower);
        assertTrue(r.m.liveHeapSamples >= lower, r.m.liveHeapSamples + " samples, lower bound " + lower);
        assertTrue(r.m.liveHeapSamples <= bound, r.m.liveHeapSamples + " samples, bound " + bound);
        assertTrue(r.m.liveHeapPeakMB >= 0.9 * r.m.liveHeapGraphMB, "the graph is live during mining");
    }

    /** 64 MB kept by the strategy during mining must appear in the live peak; the same allocation dropped must not. */
    @Test
    void retainedMemoryIsSeen() throws IOException {
        LabeledWeightedGraph g = emailEu();
        double kept = livePeakWith(g, true), dropped = livePeakWith(g, false);
        assertTrue(kept - dropped >= 60, "kept " + kept + " MB, dropped " + dropped + " MB");
    }

    private static double livePeakWith(LabeledWeightedGraph g, boolean keep) {
        Metrics m = new Metrics();
        m.liveHeap = true;
        MiningStrategy s = new MiningStrategy() {
            byte[] block;

            public String name() {
                return "test";
            }

            public boolean acceptFrequent(Pattern p, int support) {
                if (block == null) {
                    byte[] b = new byte[64 << 20];
                    b[b.length - 1] = 1;
                    if (keep) block = b;
                }
                return true;
            }
        };
        new MiningEngine(g, m).mine(15, s);
        assertTrue(m.liveHeapSamples >= 2);
        return m.liveHeapPeakMB;
    }
}
