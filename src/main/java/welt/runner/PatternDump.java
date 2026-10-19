package welt.runner;

import welt.core.CanonicalCode;
import welt.core.GraphIndex;
import welt.core.LabeledWeightedGraph;
import welt.core.Metrics;
import welt.core.MiningEngine;
import welt.core.MniSupportCounter;
import welt.core.Pattern;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes the result set of a completed cell to {@code <dir>/<dataset>_<algorithm>_<minSup>_<minWeight>.tsv},
 * one pattern per line, sorted by canonical code:
 * <pre>
 *   code   vertices   edges   weight
 * </pre>
 * {@code weight} is the exact edge bottleneck W (max over occurrences of the minimum edge weight),
 * or for WEGM (weight-labeled graph) the Max-method weight of the pattern's own edges. It is
 * computed here, after the timed runs, on a fresh counter: it does not enter any timing or counter
 * of the benchmark. Result sets are compared by canonical code.
 */
final class PatternDump {

    private PatternDump() {
    }

    static String fileName(String dataset, String algo, int minSup, double minWeight) {
        String ds = dataset.endsWith(".lg") ? dataset.substring(0, dataset.length() - 3) : dataset;
        return String.format(Locale.ROOT, "%s_%s_%d_%s.tsv", ds, algo, minSup, minWeight);
    }

    /** @return the number of patterns written */
    static int write(Path dir, LabeledWeightedGraph g, String dataset, String algo, int minSup, double minWeight,
                     Iterable<MiningEngine.FrequentPattern> patterns) throws IOException {
        return write(dir, g, false, dataset, algo, minSup, minWeight, patterns);
    }

    /** {@code weightLabels}: {@code g} is the weight-labeled graph of WEGM. */
    static int write(Path dir, LabeledWeightedGraph g, boolean weightLabels, String dataset, String algo,
                     int minSup, double minWeight, Iterable<MiningEngine.FrequentPattern> patterns) throws IOException {
        Files.createDirectories(dir);
        MniSupportCounter exact = new MniSupportCounter(g, new Metrics());
        double[] weights = new GraphIndex(g, minSup).sortedDistinctWeightsDesc;
        Map<String, Pattern> byCode = new TreeMap<>();
        for (MiningEngine.FrequentPattern fp : patterns) {
            String code = CanonicalCode.of(fp.pattern);
            if (byCode.put(code, fp.pattern) != null) {
                throw new IllegalStateException("duplicate canonical code in a result set: " + code);
            }
        }
        Path file = dir.resolve(fileName(dataset, algo, minSup, minWeight));
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("code\tvertices\tedges\tweight\n");
            for (Map.Entry<String, Pattern> e : byCode.entrySet()) {
                Pattern p = e.getValue();
                w.write(String.format(Locale.ROOT, "%s\t%d\t%d\t%s\n", e.getKey(), p.nodeCount(), p.edgeCount(),
                        weightLabels ? welt.strategy.WEGMStrategy.weightMax(p, minWeight)
                                : exact.maxBottleneckWeight(p, weights)));
            }
        }
        return byCode.size();
    }
}
