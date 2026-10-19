package welt.runner;

import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Writes a dataset in the input format of ReSuM (github.com/lady-bluecopper/ReSuM): a graph file
 * ({@code v <id> <label>} in ascending id order, then {@code e <u> <v> <edgeLabel>} once per
 * undirected edge) and a weight file (one line, the weight of the i-th edge line at position i).
 * The graph is the one the WeLT runners mine: it is read with the same reader, so duplicate
 * directed lines are already merged.
 *
 * <p>Usage: {@code ExportResumMain <dataset.lg> <outDir>} writes {@code <outDir>/<name>.lg} and
 * {@code <outDir>/<name>.w}, and prints the vertex and edge counts.
 * {@code ExportResumMain relevance <minWeight>} prints {@link #relevanceFor(double)}.
 *
 * <p>ReSuM's ALL score keeps the appearances whose edges all weigh strictly more than the
 * relevance threshold. {@link #relevanceFor(double)} gives the threshold for which "strictly
 * more" equals "at least minWeight" on doubles.
 */
public final class ExportResumMain {

    private ExportResumMain() {
    }

    /** The largest double below minWeight: w > relevanceFor(t) ⟺ w ≥ t for every double w. */
    public static double relevanceFor(double minWeight) {
        return Math.nextDown(minWeight);
    }

    public static long[] export(LabeledWeightedGraph g, Path graphFile, Path weightFile) throws IOException {
        long edges = 0;
        try (BufferedWriter gw = Files.newBufferedWriter(graphFile, StandardCharsets.UTF_8);
             BufferedWriter ww = Files.newBufferedWriter(weightFile, StandardCharsets.UTF_8)) {
            for (int v = 0; v < g.numVertices(); v++) {
                gw.write("v " + v + " " + g.vertexLabel(v) + "\n");
            }
            boolean first = true;
            for (int u = 0; u < g.numVertices(); u++) {
                for (LabeledWeightedGraph.Adjacency a : g.neighbors(u)) {
                    if (u >= a.to) continue; // each undirected edge once
                    gw.write("e " + u + " " + a.to + " " + a.edgeLabel + "\n");
                    if (!first) ww.write(' ');
                    ww.write(Double.toString(a.weight)); // exact round trip
                    first = false;
                    edges++;
                }
            }
            ww.write('\n');
        }
        return new long[]{g.numVertices(), edges};
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 2 && args[0].equals("relevance")) {
            System.out.println(Double.toString(relevanceFor(Double.parseDouble(args[1]))));
            return;
        }
        if (args.length != 2) {
            System.err.println("usage: ExportResumMain <dataset.lg> <outDir> | ExportResumMain relevance <minWeight>");
            System.exit(2);
        }
        Path in = Path.of(args[0]);
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        String name = in.getFileName().toString().replaceFirst("\\.lg$", "");
        LabeledWeightedGraph g = new LgGraphReader().read(in).graph;
        long[] n = export(g, out.resolve(name + ".lg"), out.resolve(name + ".w"));
        System.out.printf(Locale.ROOT, "%s: %d vertices, %d edges -> %s%n", name, n[0], n[1], out.toAbsolutePath().normalize());
    }
}
