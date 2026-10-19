package welt.runner;

import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.Metrics;
import welt.core.MiningEngine;
import welt.core.Pattern;
import welt.strategy.GraMiStrategy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Result sets of ReSuM's ALL score in the format of {@link PatternDump}, for comparison with the
 * other algorithms by {@code tools/compare_patterns.py}.
 *
 * <pre>
 *   ResumMain import &lt;dataset.lg&gt; &lt;patterns.p&gt; &lt;minSup&gt; &lt;minWeight&gt; &lt;outDir&gt;
 *       Reads a pattern file written by ReSuM and writes {@code <ds>_ReSuM-ALL_<minSup>_<minWeight>.tsv}.
 *   ResumMain definition &lt;dataset.lg&gt; &lt;minSup&gt; &lt;minWeight&gt; &lt;outDir&gt;
 *       Computes the ALL result set from its definition and writes {@code <ds>_ALL-definition_...tsv}.
 * </pre>
 *
 * <p>Definition (Preti et al.): the ALL score of P is its MNI support over the appearances whose
 * edges all weigh more than the relevance threshold. With the threshold of
 * {@link ExportResumMain#relevanceFor}, these are the appearances in G_{≥minWeight}, so the ALL
 * result set is the set of patterns with MNI ≥ minSup in G_{≥minWeight}: GraMi on that graph.
 *
 * <p>ReSuM pattern file: for each pattern a line {@code <i> <label>} for i = 0, 1, ..., then
 * {@code <u> <v> <edgeLabel>} per edge, followed by a line with the set of weight functions for
 * which the pattern is relevant ({@code {0}} with one function).
 */
public final class ResumMain {

    private ResumMain() {
    }

    /** Patterns of a ReSuM pattern file that are relevant for weight function 0. */
    static List<Pattern> readPatterns(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.size() % 2 != 0) throw new IOException(file + ": odd number of lines (" + lines.size() + ")");
        List<Pattern> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i += 2) {
            String mask = lines.get(i + 1).trim();
            if (!mask.startsWith("{") || !mask.endsWith("}")) throw new IOException(file + ":" + (i + 2) + ": not a mask: " + mask);
            if (!List.of(mask.substring(1, mask.length() - 1).split(",\\s*")).contains("0")) continue;
            out.add(parse(lines.get(i).trim(), file, i + 1));
        }
        return out;
    }

    static Pattern parse(String line, Path file, int lineNo) throws IOException {
        String[] t = line.split("\\s+");
        List<Integer> labels = new ArrayList<>();
        int k = 0;
        while (k + 1 < t.length && Integer.parseInt(t[k]) == labels.size()) { // vertex ids run 0, 1, 2, ...
            labels.add(Integer.parseInt(t[k + 1]));
            k += 2;
        }
        if ((t.length - k) % 3 != 0 || labels.size() < 2) {
            throw new IOException(file + ":" + lineNo + ": cannot parse pattern: " + line);
        }
        int[][] edges = new int[(t.length - k) / 3][];
        for (int e = 0; e < edges.length; e++, k += 3) {
            edges[e] = new int[]{Integer.parseInt(t[k]), Integer.parseInt(t[k + 1]), Integer.parseInt(t[k + 2])};
        }
        int[] nl = labels.stream().mapToInt(Integer::intValue).toArray();
        return Pattern.of(nl, edges);
    }

    /** G_{≥minWeight}: the same vertices, the edges weighing at least minWeight. */
    static LabeledWeightedGraph atLeast(LabeledWeightedGraph g, double minWeight) {
        LabeledWeightedGraph h = new LabeledWeightedGraph(g.numVertices());
        for (int v = 0; v < g.numVertices(); v++) h.setVertexLabel(v, g.vertexLabel(v));
        for (int u = 0; u < g.numVertices(); u++) {
            for (LabeledWeightedGraph.Adjacency a : g.neighbors(u)) {
                if (u < a.to && a.weight >= minWeight) h.addUndirectedEdge(u, a.to, a.edgeLabel, a.weight);
            }
        }
        return h;
    }

    /** The ALL result set by its definition. */
    static List<MiningEngine.FrequentPattern> definition(LabeledWeightedGraph g, int minSup, double minWeight) {
        return new MiningEngine(atLeast(g, minWeight), new Metrics()).mine(minSup, new GraMiStrategy());
    }

    private static List<MiningEngine.FrequentPattern> asResults(List<Pattern> ps) {
        List<MiningEngine.FrequentPattern> out = new ArrayList<>();
        for (Pattern p : ps) out.add(new MiningEngine.FrequentPattern(p, -1));
        return out;
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 6 && args[0].equals("import")) {
            Path ds = Path.of(args[1]);
            LabeledWeightedGraph g = new LgGraphReader().read(ds).graph;
            List<Pattern> ps = readPatterns(Path.of(args[2]));
            int n = PatternDump.write(Path.of(args[5]), g, ds.getFileName().toString(), "ReSuM-ALL",
                    Integer.parseInt(args[3]), Double.parseDouble(args[4]), asResults(ps));
            System.out.printf(Locale.ROOT, "ReSuM-ALL: %d patterns%n", n);
        } else if (args.length == 5 && args[0].equals("definition")) {
            Path ds = Path.of(args[1]);
            LabeledWeightedGraph g = new LgGraphReader().read(ds).graph;
            int minSup = Integer.parseInt(args[2]);
            double minWeight = Double.parseDouble(args[3]);
            int n = PatternDump.write(Path.of(args[4]), g, ds.getFileName().toString(), "ALL-definition",
                    minSup, minWeight, definition(g, minSup, minWeight));
            System.out.printf(Locale.ROOT, "ALL-definition: %d patterns%n", n);
        } else {
            System.err.println("usage: ResumMain import <dataset.lg> <patterns.p> <minSup> <minWeight> <outDir>\n"
                    + "       ResumMain definition <dataset.lg> <minSup> <minWeight> <outDir>");
            System.exit(2);
        }
    }
}
