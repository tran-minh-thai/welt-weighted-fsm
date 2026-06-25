package welt.runner;

import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * MILESTONE 1 — Data reading.
 * Prints the first 30 lines of the file, graph statistics, and a few sample edges with weights.
 *
 * Usage: java welt.runner.ReadDatasetMain datasets/citeseer.lg
 */
public final class ReadDatasetMain {

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args.length > 0 ? args[0] : "datasets/citeseer.lg");

        System.out.println("================ MILESTONE 1: DATA READING ================");
        System.out.println("File: " + file.toAbsolutePath());
        System.out.println();

        // ---- Print the first 30 raw lines to confirm the format ----
        System.out.println("---- FIRST 30 LINES (raw) ----");
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int n = 0;
            while (n < 30 && (line = br.readLine()) != null) {
                System.out.printf("%3d| %s%n", ++n, line);
            }
        }
        System.out.println();

        // ---- Read the graph with LgGraphReader (default CiteSeer convention) ----
        long t0 = System.nanoTime();
        LgGraphReader reader = new LgGraphReader();
        LgGraphReader.Result res = reader.read(file);
        long t1 = System.nanoTime();
        LabeledWeightedGraph g = res.graph;
        LgGraphReader.Stats s = res.stats;

        System.out.println("---- GRAPH STATISTICS ----");
        System.out.printf("Vertices               : %d%n", s.numVertices);
        System.out.printf("Edge lines (directed)  : %d%n", s.numDirectedEdgeLines);
        System.out.printf("Undirected edges       : %d%n", s.numUndirectedEdges);
        System.out.printf("  - reciprocal pairs merged : %d%n", s.reciprocalMerged);
        System.out.printf("  - parallel edges dropped  : %d%n", s.parallelDropped);
        System.out.printf("  - conflicting weights     : %d%n", s.conflictingWeights);
        System.out.printf("  - self-loops              : %d%n", s.selfLoops);
        System.out.printf("Vertex labels          : %d  -> %s%n", s.vertexLabels.size(), s.vertexLabels);
        System.out.printf("Edge labels (structural): %d  -> %s%n", s.edgeLabels.size(), s.edgeLabels);
        System.out.printf("Weight range           : [%.6f , %.6f]%n", s.minWeight, s.maxWeight);
        System.out.printf("Mean weight            : %.6f%n", s.meanWeight());
        System.out.printf("Edges with weight <= 0 : %d%n", s.nonPositiveWeights);
        System.out.printf("Ids remapped?          : %s%n", s.idsRemapped ? "YES" : "NO (already contiguous)");
        System.out.printf("Read time              : %.1f ms%n", (t1 - t0) / 1e6);
        System.out.println();

        // ---- Compare against expectation ----
        System.out.println("---- EXPECTATION CHECK (~3,312 vertices, ~4,732 edges) ----");
        System.out.printf("Vertices: %d (expected ~3312) -> %s%n",
                s.numVertices, s.numVertices == 3312 ? "MATCH" : "MISMATCH");
        System.out.printf("Edges: %d directed lines / %d undirected (expected ~4732)%n",
                s.numDirectedEdgeLines, s.numUndirectedEdges);
        System.out.println();

        // ---- A few sample edges with weights ----
        System.out.println("---- 10 SAMPLE EDGES (vertex u[label] -- v[label], edgeLabel, weight) ----");
        int printed = 0;
        List<String> samples = new ArrayList<>();
        for (int u = 0; u < g.numVertices() && printed < 10; u++) {
            for (LabeledWeightedGraph.Adjacency adj : g.neighbors(u)) {
                if (u < adj.to) { // print each undirected edge once
                    samples.add(String.format("  %d[%d] -- %d[%d]  edgeLabel=%d  ω=%.6f",
                            u, g.vertexLabel(u), adj.to, g.vertexLabel(adj.to),
                            adj.edgeLabel, adj.weight));
                    if (++printed >= 10) break;
                }
            }
        }
        samples.forEach(System.out::println);
        System.out.println();

        // ---- Warnings ----
        if (!s.warnings.isEmpty()) {
            System.out.println("---- WARNINGS (" + s.warnings.size() + ") ----");
            int cap = Math.min(10, s.warnings.size());
            for (int i = 0; i < cap; i++) System.out.println("  * " + s.warnings.get(i));
            if (s.warnings.size() > cap) {
                System.out.println("  ... and " + (s.warnings.size() - cap) + " more warnings.");
            }
        } else {
            System.out.println("No warnings.");
        }
    }
}
