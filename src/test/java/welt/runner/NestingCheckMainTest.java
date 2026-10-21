package welt.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import welt.core.CanonicalCode;
import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.MiningEngine;
import welt.strategy.WEGMStrategy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NestingCheckMainTest {

    @TempDir
    Path dir;

    private Path dump(String name, String... codes) throws IOException {
        Path f = dir.resolve(name);
        StringBuilder s = new StringBuilder("code\tvertices\tedges\tweight\n");
        for (String c : codes) s.append(c).append("\t2\t1\t1.0\n");
        Files.writeString(f, s.toString());
        return f;
    }

    @Test
    void codesRoundTrip() throws IOException {
        LabeledWeightedGraph g = new LgGraphReader().read(Path.of("datasets/email_eu.lg")).graph;
        RunHarness.Result r = RunHarness.runOnce(g, new RunHarness.Config("WeLT", 10, 336));
        assertTrue(r.patterns.size() > 10);
        for (MiningEngine.FrequentPattern fp : r.patterns) {
            String c = CanonicalCode.of(fp.pattern);
            assertEquals(c, CanonicalCode.of(NestingCheckMain.fromCode(c, false)));
        }
    }

    @Test
    void handMadeDumps() throws IOException {
        // two WEGM patterns with the same skeleton (labels 1-2, weights 509 and 382) and one of labels 3-3
        Path wegm = dump("w.tsv", "0 1 1 509 2;", "0 1 1 382 2;", "0 1 3 7 3;");
        Path all = dump("a.tsv", "0 1 1 0 2;", "0 1 3 0 3;", "0 1 4 0 4;");
        Path welt = dump("l.tsv", "0 1 1 0 2;", "0 1 3 0 3;", "0 1 4 0 4;", "0 1 5 0 5;");
        assertArrayEquals(new long[]{2, 3, 4, 0, 0}, NestingCheckMain.check(wegm, all, welt));
        assertArrayEquals(new long[]{2, 2, 4, 1, 0}, NestingCheckMain.check(wegm, dump("a2.tsv", "0 1 1 0 2;", "0 1 4 0 4;"), welt));
        assertArrayEquals(new long[]{2, 3, 2, 0, 1}, NestingCheckMain.check(wegm, all, dump("l2.tsv", "0 1 1 0 2;", "0 1 3 0 3;")));
        assertArrayEquals(new long[]{2, 3, -1, 0, -1}, NestingCheckMain.check(wegm, all, dir.resolve("absent.tsv")));
    }

    /** On a real cell where WEGM returns patterns, the three dumps nest. */
    @Test
    void citeseerNests() throws IOException {
        Path src = Path.of("datasets/citeseer.lg");
        LabeledWeightedGraph g = new LgGraphReader().read(src).graph;
        // the graph restricted to the edges of weight >= 96
        String heavy = Files.readAllLines(src).stream()
                .filter(l -> !l.startsWith("e ") || Double.parseDouble(l.trim().split("\\s+")[3]) >= 96)
                .collect(Collectors.joining("\n", "", "\n"));
        Path hf = dir.resolve("citeseer_heavy.lg");
        Files.writeString(hf, heavy);
        LabeledWeightedGraph h = new LgGraphReader().read(hf).graph;
        int s = 3;
        RunHarness.Result w = RunHarness.runOnce(g, new RunHarness.Config("WEGM", s, 96));
        RunHarness.Result a = RunHarness.runOnce(h, new RunHarness.Config("GraMi", s, 96));
        RunHarness.Result l = RunHarness.runOnce(g, new RunHarness.Config("WeLT", s, 96));
        Path pw = dir.resolve("p");
        PatternDump.write(pw, WEGMStrategy.weightLabeledGraph(g), true, "citeseer", "WEGM", s, 96, w.patterns);
        PatternDump.write(pw, h, "citeseer_heavy", "GraMi", s, 96, a.patterns);
        PatternDump.write(pw, g, "citeseer", "WeLT", s, 96, l.patterns);
        long[] r = NestingCheckMain.check(pw.resolve(PatternDump.fileName("citeseer", "WEGM", s, 96)),
                pw.resolve(PatternDump.fileName("citeseer_heavy", "GraMi", s, 96)),
                pw.resolve(PatternDump.fileName("citeseer", "WeLT", s, 96)));
        assertTrue(r[0] > 0 && r[1] >= r[0] && r[2] >= r[1], java.util.Arrays.toString(r));
        assertEquals(0, r[3]);
        assertEquals(0, r[4]);
    }
}
