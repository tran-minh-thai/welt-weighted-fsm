package welt.core;

import org.junit.jupiter.api.Test;
import welt.strategy.GraMiStrategy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CORE REGRESSION TEST (must always be GREEN): {@link GraMiStrategy} on the shared
 * engine must produce EXACTLY the same set of frequent subgraphs as the ORIGINAL GraMi.
 *
 * <p>Oracle = the original GraMi output run on {@code citeseer_unlabeled.lg} (all edge
 * labels = 0), stored in {@code reference/oracle/unlab_s{500,400,300}.txt}. Because
 * {@code EdgeLabelAssigner.CONSTANT_UNLABELED} also assigns label 0 to every edge,
 * running GraMiStrategy on {@code citeseer.lg} is equivalent to the original GraMi on
 * the unlabeled version.
 *
 * <p>Matching is by CANONICAL CODE (isomorphism-invariant), not merely by count.
 */
class GraMiOracleTest {

    private static final Path DATASET = Path.of("datasets/citeseer.lg");
    private static final Path ORACLE_DIR = Path.of("reference/oracle");

    private Set<String> mineCanonicalCodes(int minSup) throws IOException {
        LgGraphReader.Result res = new LgGraphReader().read(DATASET);
        Metrics m = new Metrics();
        MiningEngine engine = new MiningEngine(res.graph, m);
        List<MiningEngine.FrequentPattern> fps = engine.mine(minSup, new GraMiStrategy());
        Set<String> codes = new TreeSet<>();
        for (MiningEngine.FrequentPattern fp : fps) codes.add(CanonicalCode.of(fp.pattern));
        return codes;
    }

    /** Parse the GraMi oracle file (time, count, then "i:\n v.. e.." blocks) into canonical codes. */
    private Set<String> oracleCanonicalCodes(int minSup) throws IOException {
        Path f = ORACLE_DIR.resolve("unlab_s" + minSup + ".txt");
        List<String> lines = Files.readAllLines(f);
        Set<String> codes = new TreeSet<>();
        Map<Integer, Integer> vLabel = new HashMap<>();
        List<int[]> edges = new ArrayList<>();
        boolean inBlock = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.endsWith(":")) { // pattern block header "i:"
                if (inBlock) codes.add(buildCode(vLabel, edges));
                vLabel = new HashMap<>();
                edges = new ArrayList<>();
                inBlock = true;
            } else if (line.startsWith("v ")) {
                String[] p = line.split("\\s+");
                vLabel.put(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            } else if (line.startsWith("e ")) {
                String[] p = line.split("\\s+");
                edges.add(new int[]{Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                        (int) Double.parseDouble(p[3])});
            }
            // skip the first 2 lines (time, count) — they do not match the branches above
        }
        if (inBlock) codes.add(buildCode(vLabel, edges));
        return codes;
    }

    private String buildCode(Map<Integer, Integer> vLabel, List<int[]> edges) {
        int n = vLabel.size();
        int[] labels = new int[n];
        for (Map.Entry<Integer, Integer> e : vLabel.entrySet()) labels[e.getKey()] = e.getValue();
        return CanonicalCode.of(Pattern.of(labels, edges.toArray(new int[0][])));
    }

    @Test
    void matchesGraMiOracleAtSupport500() throws IOException {
        assertEquals(oracleCanonicalCodes(500), mineCanonicalCodes(500));
    }

    @Test
    void matchesGraMiOracleAtSupport400() throws IOException {
        assertEquals(oracleCanonicalCodes(400), mineCanonicalCodes(400));
    }

    @Test
    void matchesGraMiOracleAtSupport300() throws IOException {
        assertEquals(oracleCanonicalCodes(300), mineCanonicalCodes(300));
    }
}
