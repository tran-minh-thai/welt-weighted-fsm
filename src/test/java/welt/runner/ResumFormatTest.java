package welt.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import welt.core.CanonicalCode;
import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.MiningEngine;
import welt.core.Pattern;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Data export to ReSuM, import of its pattern files, and the ALL result set by definition. */
class ResumFormatTest {

    private static final int EL = 0;

    /** Star: centre (label 1) with three label-0 leaves, edge weights 30, 70, 90. */
    private static LabeledWeightedGraph star() {
        LabeledWeightedGraph g = new LabeledWeightedGraph(4);
        g.setVertexLabel(0, 1);
        g.setVertexLabel(1, 0);
        g.setVertexLabel(2, 0);
        g.setVertexLabel(3, 0);
        g.addUndirectedEdge(0, 1, EL, 30.0);
        g.addUndirectedEdge(0, 2, EL, 70.0);
        g.addUndirectedEdge(0, 3, EL, 90.5);
        return g;
    }

    private static Set<String> codes(List<MiningEngine.FrequentPattern> fps) {
        Set<String> s = new TreeSet<>();
        for (MiningEngine.FrequentPattern fp : fps) s.add(CanonicalCode.of(fp.pattern));
        return s;
    }

    @Test
    void relevanceThresholdTurnsStrictIntoAtLeast() {
        int compared = 0;
        for (double t : new double[]{0.0, 0.5, 40.0, 96.0, 336.0, 1e-300}) {
            for (double w : new double[]{t, Math.nextUp(t), Math.nextDown(t), t + 1, t - 1, 0.0}) {
                assertEquals(w >= t, w > ExportResumMain.relevanceFor(t), "t=" + t + " w=" + w);
                compared++;
            }
        }
        assertEquals(36, compared);
    }

    @Test
    void exportWritesEveryEdgeOnceWithItsWeight(@TempDir Path dir) throws IOException {
        long[] n = ExportResumMain.export(star(), dir.resolve("g.lg"), dir.resolve("g.w"));
        assertEquals(4, n[0]);
        assertEquals(3, n[1]);
        List<String> lines = Files.readAllLines(dir.resolve("g.lg"));
        assertEquals(List.of("v 0 1", "v 1 0", "v 2 0", "v 3 0", "e 0 1 0", "e 0 2 0", "e 0 3 0"), lines);
        assertEquals(List.of("30.0 70.0 90.5"), Files.readAllLines(dir.resolve("g.w")));
    }

    @Test
    void exportOfARealDatasetMatchesTheGraph(@TempDir Path dir) throws IOException {
        LabeledWeightedGraph g = new LgGraphReader().read(Path.of("datasets/email_eu.lg")).graph;
        long[] n = ExportResumMain.export(g, dir.resolve("g.lg"), dir.resolve("g.w"));
        List<String> edges = Files.readAllLines(dir.resolve("g.lg")).stream().filter(l -> l.startsWith("e ")).toList();
        String[] w = Files.readString(dir.resolve("g.w")).trim().split(" ");
        assertEquals(n[1], edges.size());
        assertEquals(n[1], w.length);
        long checked = 0;
        for (int i = 0; i < edges.size(); i++) {
            String[] t = edges.get(i).split(" ");
            LabeledWeightedGraph.Adjacency a = g.edge(Integer.parseInt(t[1]), Integer.parseInt(t[2]));
            assertEquals(a.edgeLabel, Integer.parseInt(t[3]));
            assertEquals(a.weight, Double.parseDouble(w[i]), 0.0);
            checked++;
        }
        assertTrue(checked > 1000, "edges checked: " + checked);
    }

    @Test
    void importKeepsRelevantPatternsOnly(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("x.p");
        Files.writeString(p, "0 0 1 1 2 0 0 1 0 1 2 0 \n{0}\n0 0 1 1 0 1 0 \n{}\n0 1 1 0 0 1 0 \n{0}\n");
        List<Pattern> ps = ResumMain.readPatterns(p);
        assertEquals(2, ps.size());
        assertEquals(CanonicalCode.of(Pattern.of(new int[]{0, 1, 0}, new int[][]{{0, 1, EL}, {1, 2, EL}})),
                CanonicalCode.of(ps.get(0)));
        assertEquals(3, ps.get(0).nodeCount());
        assertEquals(2, ps.get(0).edgeCount());
        assertEquals(CanonicalCode.of(Pattern.singleEdge(0, 1, EL)), CanonicalCode.of(ps.get(1)));
    }

    /** ALL keeps a pattern when minSup images exist inside G_{≥minWeight}. */
    @Test
    void definitionOnHandExample() {
        String edge = CanonicalCode.of(Pattern.singleEdge(0, 1, EL));
        String path = CanonicalCode.of(Pattern.of(new int[]{0, 1, 0}, new int[][]{{0, 1, EL}, {1, 2, EL}}));
        assertEquals(Set.of(edge, path), codes(ResumMain.definition(star(), 1, 70.0)));
        assertEquals(Set.of(edge), codes(ResumMain.definition(star(), 1, 70.5)));
        assertEquals(Set.of(), codes(ResumMain.definition(star(), 1, 91.0)));
        assertEquals(Set.of(), codes(ResumMain.definition(star(), 2, 90.5)));
        assertEquals(Set.of(edge), codes(ResumMain.definition(star(), 1, 90.5)));
    }
}
