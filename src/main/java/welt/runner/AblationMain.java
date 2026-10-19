package welt.runner;

import welt.core.CanonicalCode;
import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.MiningEngine;
import welt.core.PivotVoter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * ABLATION of WeLT on one {@code (dataset, minSup, minWeight)} cell. Each variant differs from
 * {@code full} in exactly ONE factor (except {@code noTable}, the combination P1 off + P2 off,
 * which completes the four {P1, P2} combinations with P3 on):
 * <pre>
 *   full      p1 on, p2 on (with its k = 1 part), p3 on, P3 against F, wext on, voter = WELT_VOTER, worder on
 *   noP1      p1 off            noP2     p2 off (k = 1 and k = 2 parts)   noP3     p3 off
 *   noP2single  the k = 1 part of P2 off
 *   p3R       P3 against R      noWext   wext off          voteDeg  voter = degree only
 *   noWorder  worder off        noBnb    weight branch-and-bound off
 *   noTable   p1 off and p2 off
 * </pre>
 * Every variant is measured with the same protocol as BenchmarkMain (probe, warmup, median of
 * the measured runs). Phase timers (canonical codes, filters, MNI, weight checks) are switched
 * on here only.
 *
 * <p><b>One JVM per variant.</b> With {@code ABLATION_VARIANT=<name>} only that variant is
 * measured (the experiment script starts one JVM per variant, so no variant inherits the JIT
 * warm-up of another). Its reference result set then comes from one untimed run of
 * {@code full} executed AFTER the measured runs. Without it, all variants run in sequence in
 * one JVM (convenient for a quick look; the first variant is disadvantaged by JIT warm-up).
 *
 * <p><b>Invariant.</b> The factors change performance only: every completed variant must
 * return exactly the result set of {@code full}, compared by canonical code. A difference is
 * reported in the CSV ({@code invariant=FAIL}, with the number of missing and extra patterns)
 * and the process exits with code 5 after all variants ran.
 *
 * <p>Usage: {@code java welt.runner.AblationMain <ds> <minSup> <minWeight> [limitMs] [warmup] [measured]}.
 * With {@code ABLATION_CSV} set, the header and one row per variant are printed with the prefix
 * {@code CSV,}.
 */
public final class AblationMain {

    static final int EXIT_INVARIANT = 5;
    private static final boolean CSV = System.getenv("ABLATION_CSV") != null;

    /** A variant: its name, what differs from full, and the change applied to the full configuration. */
    static final class Variant {
        final String name;
        final String differs;
        final Consumer<RunHarness.Config> change;

        Variant(String name, String differs, Consumer<RunHarness.Config> change) {
            this.name = name;
            this.differs = differs;
            this.change = change;
        }
    }

    static final List<Variant> VARIANTS = List.of(
            new Variant("full", "-", c -> { }),
            new Variant("noP1", "p1=off", c -> c.p1 = false),
            new Variant("noP2", "p2=off", c -> c.p2 = false),
            new Variant("noP2single", "p2single=off", c -> c.p2single = false),
            new Variant("noP3", "p3=off", c -> c.p3 = false),
            new Variant("p3R", "p3set=R", c -> c.p3AgainstResults = true),
            new Variant("noWext", "wext=off", c -> c.wext = false),
            new Variant("voteDeg", "voter=DEGREE_ONLY", c -> c.voter = PivotVoter.DEGREE_ONLY),
            new Variant("noWorder", "worder=off", c -> c.weightOrder = false),
            new Variant("noBnb", "bnb=off", c -> c.bnb = false),
            new Variant("noTable", "p1=off+p2=off (combination)", c -> { c.p1 = false; c.p2 = false; }));

    /** The full WeLT configuration: voter from WELT_VOTER (see BenchmarkMain), every mechanism on. */
    static RunHarness.Config full(int minSup, double minWeight, long limitMs) {
        RunHarness.Config c = BenchmarkMain.configure(new RunHarness.Config("WeLT", minSup, minWeight));
        c.weightOrder = true;
        c.limitMs = limitMs;
        c.phaseTiming = true;
        return c;
    }

    static Set<String> codes(RunHarness.Result r) {
        Set<String> s = new TreeSet<>();
        for (MiningEngine.FrequentPattern fp : r.patterns) s.add(CanonicalCode.of(fp.pattern));
        return s;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--variants")) { // for the experiment script
            for (Variant v : VARIANTS) System.out.println(v.name);
            return;
        }
        Path file = Path.of(args.length > 0 ? args[0] : "datasets/citeseer.lg");
        int minSup = args.length > 1 ? Integer.parseInt(args[1]) : 250;
        double minWeight = args.length > 2 ? Double.parseDouble(args[2]) : 96.0;
        long limitMs = args.length > 3 ? Long.parseLong(args[3]) : 0; // 0 = no limit
        int warmup = args.length > 4 ? Integer.parseInt(args[4]) : 1;
        int measured = args.length > 5 ? Integer.parseInt(args[5]) : 5;

        LabeledWeightedGraph g = new LgGraphReader().read(file).graph;
        String ds = file.getFileName().toString();
        System.out.printf(Locale.ROOT, "== Ablation | %s | minSup=%d minWeight=%.1f | limit=%s | warmup=%d measured=%d ==%n",
                ds, minSup, minWeight, limitMs == 0 ? "none" : (limitMs + "ms"), warmup, measured);
        System.out.printf(Locale.ROOT, "%-9s %-6s %6s %8s %10s %12s %12s %10s%n",
                "variant", "status", "R", "M", "MINEmni", "backtrack", "total(ms)", "invariant");

        String only = System.getenv("ABLATION_VARIANT");
        List<Variant> selected = new ArrayList<>();
        for (Variant v : VARIANTS) if (only == null || only.isBlank() || v.name.equals(only)) selected.add(v);
        if (selected.isEmpty()) throw new IllegalArgumentException("unknown ABLATION_VARIANT " + only);
        boolean single = selected.size() == 1;

        Set<String> fullCodes = null;
        boolean fullOk = false;
        boolean anyFail = false;
        boolean headerPrinted = false;
        for (Variant v : selected) {
            RunHarness.Config cfg = full(minSup, minWeight, limitMs);
            v.change.accept(cfg);
            BenchmarkMain.Cell cell = BenchmarkMain.measureCell(g, cfg, ds, warmup, measured);
            RunHarness.Result last = cell.runs.get(cell.runs.size() - 1);
            boolean ok = cell.status.equals("OK");

            if (single && !v.name.equals("full")) {
                // reference result set: one untimed full run, after the measured runs
                RunHarness.Config ref = full(minSup, minWeight, limitMs);
                ref.phaseTiming = false;
                RunHarness.Result rr = RunHarness.runOnce(g, ref);
                fullOk = rr.status.equals("OK");
                fullCodes = fullOk ? codes(rr) : null;
            }
            String invariant;
            int missing = 0, extra = 0;
            if (v.name.equals("full")) {
                fullOk = ok;
                fullCodes = ok ? codes(last) : null;
                invariant = ok ? "REF" : "NA";
            } else if (!ok || !fullOk) {
                invariant = "NA"; // a partial result set cannot be compared
            } else {
                Set<String> mine = codes(last);
                for (String c : fullCodes) if (!mine.contains(c)) missing++;
                for (String c : mine) if (!fullCodes.contains(c)) extra++;
                invariant = (missing == 0 && extra == 0) ? "OK" : "FAIL";
            }
            if (invariant.equals("FAIL")) {
                anyFail = true;
                System.err.printf("ERROR: variant %s returns a different result set than full on %s minSup=%d "
                        + "(%d missing, %d extra)%n", v.name, ds, minSup, missing, extra);
            }

            List<RunHarness.Result> runs = cell.runs;
            System.out.printf(Locale.ROOT, "%-9s %-6s %6d %8d %10d %12d %12.1f %10s%n",
                    v.name, cell.status, last.m.resultCount, last.m.candidateCount, last.mniIsoCallsMine(),
                    last.m.backtrackNodes, BenchmarkMain.median(runs, r -> r.totalMs), invariant);
            if (!CSV) continue;
            RunHarness.Row row = new RunHarness.Row()
                    .put("dataset", ds)
                    .put("variant", v.name)
                    .put("differsFromFull", v.differs)
                    .put("minSup", minSup)
                    .put("minWeight", minWeight)
                    .put("limitMs", limitMs)
                    .put("warmup", warmup)
                    .put("runs", runs.size())
                    .put("p1", cfg.p1 ? "on" : "off")
                    .put("p2", cfg.p2 ? "on" : "off")
                    .put("p2single", cfg.p2single ? "on" : "off")
                    .put("p3", cfg.p3 ? "on" : "off")
                    .put("p3set", cfg.p3AgainstResults ? "R" : "F")
                    .put("wext", cfg.wext ? "on" : "off")
                    .put("voter", cfg.voter.label())
                    .put("worder", cfg.weightOrder ? "on" : "off")
                    .put("bnb", cfg.bnb ? "on" : "off")
                    .put("voterSource", BenchmarkMain.voterSource("WeLT"))
                    .put("status", cell.status)
                    .put("partial", ok ? 0 : 1)
                    .put("invariant", invariant)
                    .put("missingVsFull", invariant.equals("NA") || invariant.equals("REF") ? "" : missing)
                    .put("extraVsFull", invariant.equals("NA") || invariant.equals("REF") ? "" : extra)
                    .put("N", last.m.generated)
                    .put("prunedP1", last.m.prunedByP1)
                    .put("prunedP2", last.m.prunedByP2)
                    .put("prunedP2Single", last.m.prunedByP2Single)
                    .put("prunedP3", last.m.prunedByApriori)
                    .put("M", last.m.candidateCount)
                    .put("Mtotal", last.candidatesTotal())
                    .put("Mprime", last.m.mniFrequent)
                    .put("R", last.m.resultCount)
                    .put("extended", last.m.extended)
                    .put("mniIsoCallsMine", last.mniIsoCallsMine())
                    .put("weightIsoCalls", last.m.weightIsoCalls)
                    .put("backtrack", last.m.backtrackNodes)
                    .put("backtrackMni", last.m.backtrackMni)
                    .put("backtrackWeight", last.m.backtrackWeight)
                    .put("tableSize", last.tableSize)
                    .put("tableBuildMs", BenchmarkMain.median(runs, r -> r.tableBuildMs))
                    .put("canonMs", BenchmarkMain.median(runs, r -> r.m.canonNanos / 1e6))
                    .put("filterMs", BenchmarkMain.median(runs, r -> r.m.filterNanos / 1e6))
                    .put("mniMs", BenchmarkMain.median(runs, r -> r.m.mniNanos / 1e6))
                    .put("weightMs", BenchmarkMain.median(runs, r -> r.m.weightNanos / 1e6))
                    .put("voteMs", BenchmarkMain.median(runs, r -> r.m.voteNanos / 1e6))
                    .put("mineMs", BenchmarkMain.median(runs, r -> r.mineMs))
                    .put("totalMs", BenchmarkMain.median(runs, r -> r.totalMs))
                    .put("totalMinMs", BenchmarkMain.min(runs, r -> r.totalMs))
                    .put("totalMaxMs", BenchmarkMain.max(runs, r -> r.totalMs))
                    .put("totalSdMs", BenchmarkMain.sd(runs, r -> r.totalMs))
                    .put("peakMemMB", BenchmarkMain.median(runs, r -> r.peakMB));
            if (!headerPrinted) {
                System.out.println("CSV," + row.header());
                headerPrinted = true;
            }
            System.out.println("CSV," + row.values());
        }
        System.out.println("(Every completed variant must return the result set of full; invariant=OK means identical canonical codes.)");
        if (anyFail) System.exit(EXIT_INVARIANT);
        if (BenchmarkMain.anyOom()) System.exit(BenchmarkMain.EXIT_OOM);
    }
}
