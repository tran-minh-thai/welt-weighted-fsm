package welt.runner;

import welt.core.CanonicalCode;
import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.MiningEngine;
import welt.core.PivotVoter;
import welt.core.VotingStats;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * One WeLT run with the voting-analysis counters, printed as one JSON line prefixed {@code JSON,}.
 *
 * <pre>
 *   java welt.runner.VotingProbeMain &lt;ds&gt; &lt;minSup&gt; &lt;minWeight&gt; [limitMs]
 * </pre>
 *
 * The WeLT settings come from {@link BenchmarkMain#configure} ({@code WELT_VOTER}, {@code WELT_WORDER},
 * table options). {@code WELT_ORDER_MODE} is {@code vote} (default), {@code index}, {@code reverse} or
 * {@code random:<seed>}; {@code WELT_VOTER_GRID} lists the voters analysed at every call as
 * {@code a,b,c;a,b,c;...}. Counters are deterministic; the run time is printed for information only.
 */
public final class VotingProbeMain {

    private VotingProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        int minSup = Integer.parseInt(args[1]);
        double minWeight = Double.parseDouble(args[2]);
        long limitMs = args.length > 3 ? Long.parseLong(args[3]) : 0;
        LabeledWeightedGraph g = new LgGraphReader().read(file).graph;

        RunHarness.Config cfg = BenchmarkMain.configure(new RunHarness.Config("WeLT", minSup, minWeight));
        cfg.limitMs = limitMs;
        String mode = System.getenv().getOrDefault("WELT_ORDER_MODE", "vote");
        List<PivotVoter> grid = new ArrayList<>();
        String spec = System.getenv().getOrDefault("WELT_VOTER_GRID", "");
        for (String v : spec.split(";")) if (!v.isBlank()) grid.add(PivotVoter.parse(v));
        if (grid.isEmpty()) throw new IllegalArgumentException("WELT_VOTER_GRID is empty");
        cfg.votingStats = new VotingStats(grid);

        RunHarness.Result r = RunHarness.runOnce(g, cfg);

        TreeSet<String> codes = new TreeSet<>();
        for (MiningEngine.FrequentPattern fp : r.patterns) codes.add(CanonicalCode.of(fp.pattern));
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (String c : codes) md.update((c + "\n").getBytes(StandardCharsets.UTF_8));
        StringBuilder hash = new StringBuilder();
        for (byte b : md.digest()) hash.append(String.format("%02x", b));

        StringBuilder out = new StringBuilder("JSON,{");
        out.append(String.format(Locale.ROOT,
                "\"dataset\":\"%s\",\"minSup\":%d,\"minWeight\":%s,\"voter\":\"%s\",\"worder\":\"%s\",\"mode\":\"%s\","
                        + "\"status\":\"%s\",\"N\":%d,\"M\":%d,\"Mprime\":%d,\"R\":%d,\"result_sha256\":\"%s\","
                        + "\"backtrack\":%d,\"backtrackMni\":%d,\"backtrackWeight\":%d,\"mniIsoCalls\":%d,"
                        + "\"weightIsoCalls\":%d,\"mniIsoCallsMine\":%d,\"tableBuildMniIsoCalls\":%d,"
                        + "\"tableBuildWeightIsoCalls\":%d,\"supportReused\":%d,\"weightReused\":%d,\"totalMs\":%.3f,",
                file.getFileName(), minSup, minWeight, cfg.voter.label(), cfg.weightOrder ? "on" : "off", mode,
                r.status, r.m.generated, r.m.candidateCount, r.m.mniFrequent, r.m.resultCount, hash,
                r.m.backtrackNodes, r.m.backtrackMni, r.m.backtrackWeight, r.m.mniIsoCalls, r.m.weightIsoCalls,
                r.mniIsoCallsMine(), r.m.tableBuildMniIsoCalls, r.m.tableBuildWeightIsoCalls, r.m.supportReused,
                r.m.weightReused, r.totalMs));
        String stats = cfg.votingStats.json();
        out.append("\"stats\":").append(stats).append('}');
        System.out.println(out);
    }
}
