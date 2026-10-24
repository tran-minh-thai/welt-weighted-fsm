package welt.runner;

import welt.core.CanonicalCode;
import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.Metrics;
import welt.core.MniSupportCounter;
import welt.core.Pattern;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Exports the MNI image sets of a dumped pattern set, for the downstream node-classification
 * features:
 * <pre>
 *   java welt.runner.ImageSetMain &lt;graph.lg&gt; &lt;tau_w&gt; &lt;patterns.tsv&gt; &lt;out.tsv&gt; [filtered|full]
 * </pre>
 * {@code filtered} (the default) takes the images in G restricted to the edges of weight &gt;= tau_w,
 * {@code full} in G itself; the description below is for {@code filtered}, and {@code full} is
 * the same with G in place of the filtered graph and without the weight check.
 * {@code patterns.tsv} is a {@link PatternDump} file. Every pattern is decoded from its
 * canonical code (the decoding is checked by re-encoding it), and for every pattern vertex u
 * the output lists the vertices v of G such that u maps to v in at least one occurrence of the
 * pattern inside G restricted to the edges of weight &gt;= tau_w ({@link #filterByWeight}).
 * These are the valid values of the MNI definition, computed without early stopping by
 * {@link MniSupportCounter#exactImageSets} on the filtered graph.
 *
 * <p>Output, one line per (pattern, pattern vertex), patterns in file order:
 * <pre>
 *   pattern   vertex   code   size   images (space-separated vertex ids, ascending)
 * </pre>
 * A pattern of an edge-bottleneck result set has W &gt;= tau_w, so it embeds in the filtered
 * graph and none of its image sets can be empty; an empty set stops the export (exit 2).
 * The export time is printed as {@code imageSetMs}; it is not part of any mining measurement.
 */
public final class ImageSetMain {

    private ImageSetMain() {
    }

    /** G restricted to its edges of weight >= tauW: every vertex (label and weight) is kept. */
    static LabeledWeightedGraph filterByWeight(LabeledWeightedGraph g, double tauW) {
        LabeledWeightedGraph f = new LabeledWeightedGraph(g.numVertices());
        for (int v = 0; v < g.numVertices(); v++) {
            f.setVertexLabel(v, g.vertexLabel(v));
            f.setVertexWeight(v, g.vertexWeight(v));
        }
        for (int a = 0; a < g.numVertices(); a++) {
            for (LabeledWeightedGraph.Adjacency e : g.neighbors(a)) {
                if (a < e.to && e.weight >= tauW) f.addUndirectedEdge(a, e.to, e.edgeLabel, e.weight);
            }
        }
        return f;
    }

    /**
     * Decodes a canonical code ({@link CanonicalCode}: DFS-code tuples {@code i j li le lj;})
     * into a pattern whose vertex k is the vertex with DFS timestamp k.
     */
    static Pattern decode(String code) {
        String[] tuples = code.split(";");
        int[][] t = new int[tuples.length][];
        int n = 0;
        for (int k = 0; k < tuples.length; k++) {
            String[] f = tuples[k].trim().split(" ");
            if (f.length != 5) throw new IllegalArgumentException("not a DFS-code tuple: '" + tuples[k] + "' in " + code);
            t[k] = new int[5];
            for (int x = 0; x < 5; x++) t[k][x] = Integer.parseInt(f[x]);
            n = Math.max(n, Math.max(t[k][0], t[k][1]) + 1);
        }
        int[] labels = new int[n];
        Arrays.fill(labels, Integer.MIN_VALUE);
        int[][] edges = new int[t.length][];
        for (int k = 0; k < t.length; k++) {
            setLabel(labels, t[k][0], t[k][2], code);
            setLabel(labels, t[k][1], t[k][4], code);
            edges[k] = new int[]{t[k][0], t[k][1], t[k][3]};
        }
        for (int v = 0; v < n; v++) {
            if (labels[v] == Integer.MIN_VALUE) throw new IllegalArgumentException("vertex " + v + " has no edge in " + code);
        }
        Pattern p = Pattern.of(labels, edges);
        if (p.edgeCount() != t.length) throw new IllegalArgumentException("repeated edge in " + code);
        return p;
    }

    private static void setLabel(int[] labels, int v, int label, String code) {
        if (labels[v] != Integer.MIN_VALUE && labels[v] != label) {
            throw new IllegalArgumentException("vertex " + v + " has two labels in " + code);
        }
        labels[v] = label;
    }

    /** One pattern of a dump file: its code and the vertex and edge counts recorded with it. */
    static final class Dumped {
        final String code;
        final int vertices, edges;
        final double weight;

        Dumped(String code, int vertices, int edges, double weight) {
            this.code = code;
            this.vertices = vertices;
            this.edges = edges;
            this.weight = weight;
        }
    }

    /** Reads a {@link PatternDump} file: header {@code code vertices edges weight}, or the earlier one with {@code mni}. */
    static List<Dumped> readDump(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String header = lines.isEmpty() ? "" : lines.get(0);
        int fields;
        if (header.equals("code\tvertices\tedges\tweight")) fields = 4;
        else if (header.equals("code\tvertices\tedges\tmni\tweight")) fields = 5;
        else throw new IOException(file + ": not a pattern dump (header '" + header + "')");
        List<Dumped> out = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t");
            if (f.length != fields) throw new IOException(file + ": expected " + fields + " fields, got " + f.length + ": " + line);
            out.add(new Dumped(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]), Double.parseDouble(f[fields - 1])));
        }
        return out;
    }

    /** The decoded pattern of one dump line, checked against the counts and the code of that line. */
    static Pattern decodeChecked(Dumped d) {
        Pattern p = decode(d.code);
        if (p.nodeCount() != d.vertices || p.edgeCount() != d.edges) {
            throw new IllegalStateException("decoded " + p.nodeCount() + " vertices and " + p.edgeCount()
                    + " edges, the dump records " + d.vertices + " and " + d.edges + ": " + d.code);
        }
        String again = CanonicalCode.of(p);
        if (!again.equals(d.code)) {
            throw new IllegalStateException("decoding is not the inverse of the code: " + d.code + " -> " + again);
        }
        return p;
    }

    public static void main(String[] args) throws Exception {
        int code = run(args);
        if (code != 0) System.exit(code);
    }

    /** The export; returns 0, 1 (usage or input) or 2 (an empty image set or a pattern below tau_w). */
    static int run(String[] args) throws Exception {
        if (args.length != 4 && args.length != 5) {
            System.err.println("usage: ImageSetMain <graph.lg> <tau_w> <patterns.tsv> <out.tsv> [filtered|full]");
            return 1;
        }
        String mode = args.length == 5 ? args[4] : "filtered";
        if (!mode.equals("filtered") && !mode.equals("full")) {
            System.err.println("ERROR: the image graph must be filtered or full, got " + mode);
            return 1;
        }
        boolean full = mode.equals("full");
        Path graphFile = Path.of(args[0]);
        double tauW = Double.parseDouble(args[1]);
        Path dumpFile = Path.of(args[2]);
        Path outFile = Path.of(args[3]);

        LgGraphReader.Result read = new LgGraphReader().read(graphFile);
        if (read.stats.idsRemapped) {
            System.err.println("ERROR: " + graphFile + " has non-contiguous vertex ids; image ids would not be file ids");
            return 1;
        }
        List<Dumped> dumped = readDump(dumpFile);
        long t0 = System.nanoTime();
        LabeledWeightedGraph filtered = full ? read.graph : filterByWeight(read.graph, tauW);
        MniSupportCounter counter = new MniSupportCounter(filtered, new Metrics());
        int lines = 0;
        long cells = 0;
        if (outFile.getParent() != null) Files.createDirectories(outFile.getParent());
        try (Writer w = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
            w.write("pattern\tvertex\tcode\tsize\timages\n");
            for (int k = 0; k < dumped.size(); k++) {
                Dumped d = dumped.get(k);
                if (!full && d.weight < tauW) {
                    System.err.println("ERROR: pattern " + k + " has W=" + d.weight + " < tau_w=" + tauW + ": " + d.code);
                    return 2;
                }
                int[][] sets = counter.exactImageSets(decodeChecked(d));
                for (int u = 0; u < sets.length; u++) {
                    if (sets[u].length == 0) {
                        System.err.println("ERROR: pattern " + k + " vertex " + u + " has no image in the " + mode
                                + " graph (tau_w=" + tauW + ", W=" + d.weight + "): " + d.code);
                        return 2;
                    }
                    StringBuilder sb = new StringBuilder();
                    for (int x = 0; x < sets[u].length; x++) {
                        if (x > 0) sb.append(' ');
                        sb.append(sets[u][x]);
                    }
                    w.write(k + "\t" + u + "\t" + d.code + "\t" + sets[u].length + "\t" + sb + "\n");
                    lines++;
                    cells += sets[u].length;
                }
            }
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.printf(Locale.ROOT, "images=%s patterns=%d featureColumns=%d nonzeros=%d imageGraphEdges=%d of %d imageSetMs=%.1f -> %s%n",
                mode, dumped.size(), lines, cells, filtered.numEdges(), read.graph.numEdges(), ms, outFile);
        return 0;
    }
}
