package vn.vrp.algorithm.aco;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import vn.vrp.algorithm.IterationSnapshot;
import vn.vrp.algorithm.SolutionSupport;
import vn.vrp.algorithm.SolverOptions;
import vn.vrp.algorithm.SolverRun;
import vn.vrp.algorithm.VrpSolver;
import vn.vrp.algorithm.greedy.GreedyCvrpSolver;
import vn.vrp.model.Customer;
import vn.vrp.model.ProblemInstance;
import vn.vrp.model.Solution;

/**
 * Ant Colony System cho giant tour. Pheromone học trên cặp order liên tiếp,
 * còn decoder Split chịu trách nhiệm capacity, vehicle type và time window.
 */
public final class AntColonySolver implements VrpSolver {
    public static final String CODE = "ACO_MACS_VRPTW";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public SolverRun solve(ProblemInstance problem, SolverOptions options) {
        long started = System.nanoTime();
        long deadline = options.timeLimitSeconds() <= 0
                ? Long.MAX_VALUE
                : started + options.timeLimitSeconds() * 1_000_000_000L;
        Random random = new Random(options.seed());
        int antCount = options.integer("ANT_COUNT", 30, 1, 500);
        double alpha = options.real("ALPHA", 1.0, 0, 10);
        double beta = options.real("BETA", 2.0, 0, 10);
        double rho = options.real("RHO", 0.1, 0.0001, 1);
        double q0 = options.real("Q0", 0.9, 0, 1);
        double pheromoneMin = options.real("PHEROMONE_MIN", 0.0001, 1e-9, 1e6);
        double pheromoneMax = options.real("PHEROMONE_MAX", 10.0, pheromoneMin, 1e9);

        int customerCount = problem.customers().size();
        double[][] pheromone = new double[customerCount + 1][customerCount];
        for (double[] row : pheromone) {
            java.util.Arrays.fill(row, 1.0);
        }
        Map<Long, Integer> customerIndex = new HashMap<>();
        for (int index = 0; index < customerCount; index++) {
            customerIndex.put(problem.customers().get(index).orderId(), index);
        }

        Solution globalBest = initialSolution(problem, started, random);
        List<Customer> globalBestGenes = flatten(globalBest);
        int bestIteration = 0;
        List<IterationSnapshot> history = new ArrayList<>();

        for (int iteration = 1;
                iteration <= options.iterationLimit() && System.nanoTime() < deadline;
                iteration++) {
            Solution iterationBest = null;
            List<Customer> iterationBestGenes = null;
            int feasibleCount = 0;

            for (int ant = 0;
                    ant < antCount && System.nanoTime() < deadline;
                    ant++) {
                List<Customer> genes = constructTour(
                        problem,
                        pheromone,
                        customerIndex,
                        alpha,
                        beta,
                        q0,
                        random);
                var decoded = SolutionSupport.decodePermutation(problem, genes, started);
                if (decoded.isPresent()) {
                    feasibleCount++;
                    Solution solution = decoded.get();
                    if (iterationBest == null || SolutionSupport.better(solution, iterationBest)) {
                        iterationBest = solution;
                        iterationBestGenes = genes;
                    }
                }
            }

            evaporate(pheromone, rho, pheromoneMin);
            if (iterationBest != null) {
                deposit(
                        pheromone,
                        iterationBestGenes,
                        customerIndex,
                        1.0 / Math.max(1e-9, iterationBest.totalDistance()),
                        pheromoneMax);
                if (SolutionSupport.better(iterationBest, globalBest)) {
                    globalBest = iterationBest;
                    globalBestGenes = iterationBestGenes;
                    bestIteration = iteration;
                }
            }
            // Elitist reinforcement giúp giữ nghiệm tốt nhất qua các vòng bay hơi.
            deposit(
                    pheromone,
                    globalBestGenes,
                    customerIndex,
                    1.0 / Math.max(1e-9, globalBest.totalDistance()),
                    pheromoneMax);

            Solution current = iterationBest == null ? globalBest : iterationBest;
            history.add(new IterationSnapshot(
                    iteration,
                    (System.nanoTime() - started) / 1_000_000,
                    SolutionSupport.objective(current),
                    SolutionSupport.objective(globalBest),
                    current.routes().size(),
                    globalBest.routes().size(),
                    current.totalDistance(),
                    globalBest.totalDistance(),
                    feasibleCount,
                    pheromoneSpread(pheromone),
                    "ants=" + antCount));
        }

        if (history.isEmpty()) {
            history.add(new IterationSnapshot(
                    0,
                    (System.nanoTime() - started) / 1_000_000,
                    SolutionSupport.objective(globalBest),
                    SolutionSupport.objective(globalBest),
                    globalBest.routes().size(),
                    globalBest.routes().size(),
                    globalBest.totalDistance(),
                    globalBest.totalDistance(),
                    1,
                    pheromoneSpread(pheromone),
                    "Initial solution"));
        }

        return new SolverRun(
                SolutionSupport.finish(globalBest, started),
                history,
                bestIteration);
    }

    private Solution initialSolution(ProblemInstance problem, long started, Random random) {
        try {
            return new GreedyCvrpSolver().solve(problem);
        } catch (IllegalStateException ignored) {
            for (int attempt = 0; attempt < 1_000; attempt++) {
                List<Customer> genes = new ArrayList<>(problem.customers());
                java.util.Collections.shuffle(genes, random);
                var solution = SolutionSupport.decodePermutation(problem, genes, started);
                if (solution.isPresent()) {
                    return solution.get();
                }
            }
            throw new IllegalStateException("ACO không tìm được nghiệm khởi tạo khả thi");
        }
    }

    private List<Customer> constructTour(
            ProblemInstance problem,
            double[][] pheromone,
            Map<Long, Integer> customerIndex,
            double alpha,
            double beta,
            double q0,
            Random random) {
        Set<Customer> unvisited = new LinkedHashSet<>(problem.customers());
        List<Customer> tour = new ArrayList<>();
        long currentLocation = problem.depot().locationId();
        int previousIndex = problem.customers().size(); // Hàng pheromone đại diện depot.

        while (!unvisited.isEmpty()) {
            List<Customer> candidates = new ArrayList<>(unvisited);
            double[] weights = new double[candidates.size()];
            double total = 0;
            for (int index = 0; index < candidates.size(); index++) {
                Customer customer = candidates.get(index);
                int targetIndex = customerIndex.get(customer.orderId());
                double distance = problem.arc(currentLocation, customer.locationId()).distance();
                double urgency = 1.0 + 1.0 / Math.max(1, customer.dueTime() - customer.readyTime());
                double heuristic = urgency / Math.max(1e-6, distance);
                double weight = Math.pow(pheromone[previousIndex][targetIndex], alpha)
                        * Math.pow(heuristic, beta);
                weights[index] = weight;
                total += weight;
            }

            int selectedIndex;
            if (random.nextDouble() < q0 || total <= 0) {
                selectedIndex = 0;
                for (int index = 1; index < weights.length; index++) {
                    if (weights[index] > weights[selectedIndex]) {
                        selectedIndex = index;
                    }
                }
            } else {
                double target = random.nextDouble() * total;
                double cumulative = 0;
                selectedIndex = weights.length - 1;
                for (int index = 0; index < weights.length; index++) {
                    cumulative += weights[index];
                    if (cumulative >= target) {
                        selectedIndex = index;
                        break;
                    }
                }
            }

            Customer selected = candidates.get(selectedIndex);
            tour.add(selected);
            unvisited.remove(selected);
            currentLocation = selected.locationId();
            previousIndex = customerIndex.get(selected.orderId());
        }
        return tour;
    }

    private void evaporate(double[][] pheromone, double rho, double min) {
        for (double[] row : pheromone) {
            for (int column = 0; column < row.length; column++) {
                row[column] = Math.max(min, row[column] * (1 - rho));
            }
        }
    }

    private void deposit(
            double[][] pheromone,
            List<Customer> genes,
            Map<Long, Integer> customerIndex,
            double amount,
            double max) {
        int previous = genes.size();
        for (Customer customer : genes) {
            int current = customerIndex.get(customer.orderId());
            pheromone[previous][current] = Math.min(max, pheromone[previous][current] + amount);
            previous = current;
        }
    }

    private List<Customer> flatten(Solution solution) {
        List<Customer> genes = new ArrayList<>();
        solution.routes().forEach(route -> genes.addAll(route.customers()));
        return genes;
    }

    private double pheromoneSpread(double[][] pheromone) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double[] row : pheromone) {
            for (double value : row) {
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
        }
        return max - min;
    }
}
