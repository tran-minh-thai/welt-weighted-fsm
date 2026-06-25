package welt.runner;

import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.Metrics;
import welt.core.MiningEngine;
import welt.strategy.GraMiStrategy;

import java.nio.file.Path;
import java.util.List;

/**
 * Runs GraMi with an embedding-search BUDGET (approach A) to test feasibility on
 * dense / low-threshold graphs. Budget 0 = unbounded (exact).
 *
 * <p>{@code java welt.runner.BudgetRunMain datasets/string_ecoli.lg 500 5000}
 */
public final class BudgetRunMain {
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        int minSup = Integer.parseInt(args[1]);
        long budget = args.length > 2 ? Long.parseLong(args[2]) : 0;

        LabeledWeightedGraph g = new LgGraphReader().read(file).graph;
        Metrics m = new Metrics();
        MiningEngine engine = new MiningEngine(g, m);
        engine.setSearchBudget(budget);
        long t0 = System.nanoTime();
        List<MiningEngine.FrequentPattern> fps = engine.mine(minSup, new GraMiStrategy());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("%s | s=%d | budget=%s | freq=%d cand=%d mniIso=%d backtrack=%d budgetHit=%d | %dms%n",
                file.getFileName(), minSup, budget == 0 ? "∞(exact)" : String.valueOf(budget),
                fps.size(), m.candidateCount, m.mniIsoCalls, m.backtrackNodes, m.budgetExceeded, ms);
    }
}
