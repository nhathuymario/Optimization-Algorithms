package vn.vrp.algorithm.ga;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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

/** Genetic Algorithm dùng chromosome giant-tour và decoder Split theo đội xe. */
public final class GeneticAlgorithmSolver implements VrpSolver {
    public static final String CODE = "GA_GIANT_TOUR_SPLIT";

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
        int populationSize = options.integer("POPULATION_SIZE", 100, 4, 500);
        double crossoverRate = options.real("CROSSOVER_RATE", 0.9, 0, 1);
        double mutationRate = options.real("MUTATION_RATE", 0.1, 0, 1);
        int eliteSize = options.integer("ELITE_SIZE", 5, 1, populationSize - 1);
        int tournamentSize = options.integer("TOURNAMENT_SIZE", 3, 2, populationSize);

        List<Chromosome> population = initializePopulation(
                problem,
                populationSize,
                random,
                started);
        Chromosome best = population.stream().min(Chromosome.BY_FITNESS).orElseThrow();
        int bestIteration = 0;
        List<IterationSnapshot> history = new ArrayList<>();

        for (int generation = 1;
                generation <= options.iterationLimit() && System.nanoTime() < deadline;
                generation++) {
            population.sort(Chromosome.BY_FITNESS);
            List<Chromosome> next = new ArrayList<>(population.subList(0, eliteSize));
            int feasibleGenerated = eliteSize;

            int attempts = 0;
            while (next.size() < populationSize
                    && attempts++ < populationSize * 20
                    && System.nanoTime() < deadline) {
                Chromosome parentA = tournament(population, tournamentSize, random);
                Chromosome parentB = tournament(population, tournamentSize, random);
                List<Customer> genes = random.nextDouble() < crossoverRate
                        ? orderedCrossover(parentA.genes(), parentB.genes(), random)
                        : new ArrayList<>(parentA.genes());
                if (random.nextDouble() < mutationRate) {
                    mutate(genes, random);
                }
                Chromosome child = decode(problem, genes, started);
                if (child != null) {
                    next.add(child);
                    feasibleGenerated++;
                }
            }

            // Nếu quá nhiều chromosome không split được, giữ cá thể tốt của thế hệ trước.
            for (int index = 0; next.size() < populationSize; index++) {
                next.add(population.get(index % population.size()));
            }
            population = next;

            Chromosome currentBest = population.stream().min(Chromosome.BY_FITNESS).orElseThrow();
            if (currentBest.fitness() < best.fitness()) {
                best = currentBest;
                bestIteration = generation;
            }
            history.add(snapshot(
                    generation,
                    started,
                    currentBest.solution(),
                    best.solution(),
                    feasibleGenerated,
                    diversity(population)));
        }

        if (history.isEmpty()) {
            history.add(snapshot(
                    0,
                    started,
                    best.solution(),
                    best.solution(),
                    population.size(),
                    diversity(population)));
        }
        return new SolverRun(
                SolutionSupport.finish(best.solution(), started),
                history,
                bestIteration);
    }

    private List<Chromosome> initializePopulation(
            ProblemInstance problem,
            int populationSize,
            Random random,
            long started) {
        List<Chromosome> population = new ArrayList<>();

        try {
            List<Customer> greedyGenes = new GreedyCvrpSolver().seedPermutation(problem);
            Chromosome greedy = decode(problem, greedyGenes, started);
            if (greedy != null) {
                population.add(greedy);
            }
        } catch (IllegalStateException ignored) {
            // Các permutation ngẫu nhiên bên dưới vẫn có thể tìm được split khả thi.
        }

        List<Customer> dueDateGenes = problem.customers().stream()
                .sorted(Comparator.comparingInt(Customer::dueTime)
                        .thenComparing(Comparator.comparingInt(Customer::priority).reversed()))
                .toList();
        Chromosome dueDate = decode(problem, dueDateGenes, started);
        if (dueDate != null) {
            population.add(dueDate);
        }

        int attempts = 0;
        while (population.size() < populationSize && attempts++ < populationSize * 100) {
            List<Customer> genes = new ArrayList<>(problem.customers());
            Collections.shuffle(genes, random);
            Chromosome chromosome = decode(problem, genes, started);
            if (chromosome != null) {
                population.add(chromosome);
            }
        }

        if (population.isEmpty()) {
            throw new IllegalStateException("GA không tìm được chromosome có Split khả thi");
        }
        while (population.size() < populationSize) {
            population.add(population.get(population.size() % population.size()));
        }
        return population;
    }

    private Chromosome decode(ProblemInstance problem, List<Customer> genes, long started) {
        return SolutionSupport.decodePermutation(problem, genes, started)
                .map(solution -> new Chromosome(
                        List.copyOf(genes),
                        solution,
                        SolutionSupport.objective(solution)))
                .orElse(null);
    }

    private Chromosome tournament(List<Chromosome> population, int size, Random random) {
        Chromosome best = null;
        for (int count = 0; count < size; count++) {
            Chromosome candidate = population.get(random.nextInt(population.size()));
            if (best == null || candidate.fitness() < best.fitness()) {
                best = candidate;
            }
        }
        return best;
    }

    /** Ordered crossover (OX) giữ mỗi order xuất hiện đúng một lần. */
    private List<Customer> orderedCrossover(
            List<Customer> parentA,
            List<Customer> parentB,
            Random random) {
        int size = parentA.size();
        if (size < 2) {
            return new ArrayList<>(parentA);
        }
        int from = random.nextInt(size);
        int to = random.nextInt(size);
        if (from > to) {
            int swap = from;
            from = to;
            to = swap;
        }

        List<Customer> child = new ArrayList<>(Collections.nCopies(size, null));
        Set<Long> used = new HashSet<>();
        for (int index = from; index <= to; index++) {
            Customer gene = parentA.get(index);
            child.set(index, gene);
            used.add(gene.orderId());
        }

        int childIndex = (to + 1) % size;
        for (int offset = 0; offset < size; offset++) {
            Customer gene = parentB.get((to + 1 + offset) % size);
            if (used.add(gene.orderId())) {
                while (child.get(childIndex) != null) {
                    childIndex = (childIndex + 1) % size;
                }
                child.set(childIndex, gene);
            }
        }
        return child;
    }

    private void mutate(List<Customer> genes, Random random) {
        if (genes.size() < 2) {
            return;
        }
        int first = random.nextInt(genes.size());
        int second = random.nextInt(genes.size());
        Collections.swap(genes, first, second);
    }

    private double diversity(List<Chromosome> population) {
        if (population.isEmpty() || population.getFirst().genes().isEmpty()) {
            return 0;
        }
        long distinctFirstGenes = population.stream()
                .map(chromosome -> chromosome.genes().getFirst().orderId())
                .distinct()
                .count();
        return distinctFirstGenes / (double) population.size();
    }

    private IterationSnapshot snapshot(
            int iteration,
            long started,
            Solution current,
            Solution best,
            int feasibleCount,
            double diversity) {
        return new IterationSnapshot(
                iteration,
                (System.nanoTime() - started) / 1_000_000,
                SolutionSupport.objective(current),
                SolutionSupport.objective(best),
                current.routes().size(),
                best.routes().size(),
                current.totalDistance(),
                best.totalDistance(),
                feasibleCount,
                diversity,
                "population=" + feasibleCount);
    }

    private record Chromosome(List<Customer> genes, Solution solution, double fitness) {
        private static final Comparator<Chromosome> BY_FITNESS =
                Comparator.comparingDouble(Chromosome::fitness);
    }
}
