package welt.runner;

import welt.core.GraphIndex;
import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.Metrics;
import welt.core.MiningEngine;
import welt.core.MniSupportCounter;
import welt.strategy.WeLTStrategy;
import welt.strategy.WeightedLookupTable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * tau_w SWEEP OF WeLT WITH ONE SHARED LOOKUP TABLE. The table depends on minSup only, so a
 * sweep over tau_w can build it once and answer every tau_w from it. One sweep = clock start,
 * graph index, table construction, then one mining run per tau_w (each with its own strategy
 * and engine, sharing the index and the table). Protocol: {@code warmup} discarded sweeps,
 * then {@code measured} sweeps; medians are reported.
 *
 * <pre>
 *   java welt.runner.SharedTableSweepMain &lt;ds&gt; &lt;minSup&gt; &lt;tau_w,tau_w,...&gt; [limitMs] [warmup] [measured]
 *   java welt.runner.SharedTableSweepMain --grid &lt;ds&gt; &lt;rho,rho,...&gt; [extra tau_w ...]
 * </pre>
 * The {@code --grid} form prints tau_w = min + rho * (max - min) over the dataset's edge-weight
 * range, followed by the extra values, one per line (used by the experiment script).
 *
 * <p>With {@code BENCH_CSV} set, prints {@code CSV,} rows: one per tau_w ({@code row=point}) and
 * one summary ({@code row=sweep}: table construction, sum of the queries, total). The matching
 * order follows WELT_VOTER / WELT_WORDER as in BenchmarkMain. {@code limitMs} bounds the whole
 * sweep; a sweep that reaches it is reported with status TO.
 */
public final class SharedTableSweepMain {

    private SharedTableSweepMain() {
    }

    static final class Sweep {
        double indexMs, tableMs, totalMs;
        int tableSize;
        String status = "OK";
        final List<double[]> points = new ArrayList<>(); // {queryMs, R, M, mniIsoCalls}
    }

    public static void main(String[] args) throws Exception {
        if (args.length >= 3 && args[0].equals("--grid")) {
            printGrid(new LgGraphReader().read(Path.of(args[1])).graph, args[2], Arrays.copyOfRange(args, 3, args.length));
            return;
        }
        Path file = Path.of(args[0]);
        int minSup = Integer.parseInt(args[1]);
        double[] taus = Arrays.stream(args[2].split(",")).mapToDouble(Double::parseDouble).toArray();
        long limitMs = args.length > 3 ? Long.parseLong(args[3]) : 0;
        int warmup = args.length > 4 ? Integer.parseInt(args[4]) : 1;
        int measured = args.length > 5 ? Integer.parseInt(args[5]) : 5;
        boolean csv = System.getenv("BENCH_CSV") != null;

        LabeledWeightedGraph g = new LgGraphReader().read(file).graph;
        String ds = file.getFileName().toString();
        RunHarness.Config cfg = BenchmarkMain.configure(new RunHarness.Config("WeLT", minSup, taus[0]));

        Sweep first = sweep(g, minSup, taus, cfg, limitMs);
        List<Sweep> runs = new ArrayList<>();
        if (!first.status.equals("OK")) {
            runs.add(first); // a sweep that hits the limit is reported alone
        } else {
            for (int i = 1; i < warmup; i++) sweep(g, minSup, taus, cfg, limitMs);
            for (int i = 0; i < measured; i++) runs.add(sweep(g, minSup, taus, cfg, limitMs));
        }
        String status = "OK";
        for (Sweep s : runs) if (!s.status.equals("OK")) status = s.status;
        Sweep last = runs.get(runs.size() - 1);

        boolean header = false;
        for (int k = 0; k < taus.length; k++) {
            final int idx = k;
            boolean done = idx < last.points.size();
            RunHarness.Row row = base(ds, minSup, limitMs, runs.size(), status, cfg)
                    .put("row", "point")
                    .put("minWeight", taus[idx])
                    .put("R", done ? (long) last.points.get(idx)[1] : "")
                    .put("M", done ? (long) last.points.get(idx)[2] : "")
                    .put("mniIsoCalls", done ? (long) last.points.get(idx)[3] : "")
                    .put("tableSize", last.tableSize)
                    .put("tableBuildMs", "")
                    .put("indexMs", "")
                    .put("queryMs", done ? median(runs, s -> idx < s.points.size() ? s.points.get(idx)[0] : Double.NaN) : "")
                    .put("totalMs", "");
            System.out.printf(Locale.ROOT, "tau_w=%-10.3f R=%-6s queryMs=%s%n", taus[idx],
                    done ? String.valueOf((long) last.points.get(idx)[1]) : "-",
                    done ? String.format(Locale.ROOT, "%.1f", median(runs, s -> s.points.get(idx)[0])) : "-");
            if (csv) {
                if (!header) { System.out.println("CSV," + row.header()); header = true; }
                System.out.println("CSV," + row.values());
            }
        }
        double sumQuery = median(runs, s -> s.points.stream().mapToDouble(p -> p[0]).sum());
        RunHarness.Row summary = base(ds, minSup, limitMs, runs.size(), status, cfg)
                .put("row", "sweep")
                .put("minWeight", "")
                .put("R", "")
                .put("M", "")
                .put("mniIsoCalls", "")
                .put("tableSize", last.tableSize)
                .put("tableBuildMs", median(runs, s -> s.tableMs))
                .put("indexMs", median(runs, s -> s.indexMs))
                .put("queryMs", sumQuery)
                .put("totalMs", median(runs, s -> s.totalMs));
        System.out.printf(Locale.ROOT, "sweep: table %.1f ms, %d queries %.1f ms, total %.1f ms, status %s%n",
                median(runs, s -> s.tableMs), taus.length, sumQuery, median(runs, s -> s.totalMs), status);
        if (csv) System.out.println("CSV," + summary.values());
    }

    private static RunHarness.Row base(String ds, int minSup, long limitMs, int runs, String status, RunHarness.Config cfg) {
        return new RunHarness.Row()
                .put("dataset", ds)
                .put("algorithm", "WeLT-shared-table")
                .put("minSup", minSup)
                .put("limitMs", limitMs)
                .put("runs", runs)
                .put("status", status)
                .put("voter", cfg.voter.label())
                .put("worder", cfg.weightOrder ? "on" : "off");
    }

    static Sweep sweep(LabeledWeightedGraph g, int minSup, double[] taus, RunHarness.Config cfg, long limitMs) {
        Sweep s = new Sweep();
        System.gc();
        long t0 = System.nanoTime();
        long deadline = limitMs > 0 ? t0 + limitMs * 1_000_000L : Long.MAX_VALUE;
        Metrics tm = new Metrics();
        MniSupportCounter tableCounter = new MniSupportCounter(g, tm);
        tableCounter.setVoter(cfg.voter);
        tableCounter.setWeightAwareOrdering(cfg.weightOrder);
        tableCounter.setDeadline(deadline);
        GraphIndex index = new GraphIndex(g, minSup);
        long t1 = System.nanoTime();
        WeightedLookupTable table = WeightedLookupTable.build(g, index, minSup, tableCounter, tm);
        long t2 = System.nanoTime();
        s.indexMs = (t1 - t0) / 1e6;
        s.tableMs = (t2 - t1) / 1e6;
        s.tableSize = table.size();
        if (tableCounter.isTimedOut()) {
            s.status = "TO";
            s.totalMs = (System.nanoTime() - t0) / 1e6;
            return s;
        }
        for (double tw : taus) {
            long q0 = System.nanoTime();
            Metrics m = new Metrics();
            MniSupportCounter counter = new MniSupportCounter(g, m);
            counter.setVoter(cfg.voter);
            counter.setWeightAwareOrdering(cfg.weightOrder);
            counter.setDeadline(deadline);
            MiningEngine engine = new MiningEngine(g, m);
            engine.setVoter(cfg.voter);
            engine.setDeadline(deadline);
            List<MiningEngine.FrequentPattern> fps = engine.mine(minSup, new WeLTStrategy(g, tw, counter, table, m), index);
            double q = (System.nanoTime() - q0) / 1e6;
            if (engine.isTimedOut() || counter.isTimedOut()) {
                s.status = "TO";
                break;
            }
            s.points.add(new double[]{q, fps.size(), m.candidateCount, m.mniIsoCalls});
        }
        s.totalMs = (System.nanoTime() - t0) / 1e6;
        return s;
    }

    private static double median(List<Sweep> runs, java.util.function.ToDoubleFunction<Sweep> f) {
        List<Double> v = new ArrayList<>();
        for (Sweep s : runs) {
            double x = f.applyAsDouble(s);
            if (!Double.isNaN(x)) v.add(x);
        }
        if (v.isEmpty()) return Double.NaN;
        Collections.sort(v);
        int n = v.size();
        return n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2.0;
    }

    private static void printGrid(LabeledWeightedGraph g, String rhos, String[] extra) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int u = 0; u < g.numVertices(); u++)
            for (LabeledWeightedGraph.Adjacency a : g.neighbors(u)) {
                min = Math.min(min, a.weight);
                max = Math.max(max, a.weight);
            }
        for (String r : rhos.split(",")) {
            double rho = Double.parseDouble(r);
            System.out.println(String.format(Locale.ROOT, "%.4f", min + rho * (max - min)));
        }
        for (String e : extra) System.out.println(e);
    }
}
