package vn.vrp.algorithm.tabu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
 * Tabu Search route-based với lân cận swap và 2-opt. Chỉ nhận các lân cận thỏa
 * capacity/time-window; aspiration cho phép move tabu nếu cải thiện global best.
 */
public final class TabuSearchSolver implements VrpSolver {
    public static final String CODE = "TABU_ROUTE";

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
        int tenure = options.integer("TABU_TENURE", 10, 1, 10_000);
        int neighborhoodSize = options.integer("NEIGHBORHOOD_SIZE", 100, 1, 10_000);
        int maxNoImprovement = options.integer("MAX_NO_IMPROVEMENT", 200, 1, 100_000);
        boolean aspiration = options.bool("ASPIRATION_ENABLED", true);
        Random random = new Random(options.seed());

        Solution initial = new GreedyCvrpSolver().solve(problem);
        Solution current = initial;
        Solution best = initial;
        int bestIteration = 0;
        int noImprovement = 0;
        Map<String, Integer> tabuUntil = new HashMap<>();
        List<IterationSnapshot> history = new ArrayList<>();

        for (int iteration = 1;
                iteration <= options.iterationLimit()
                        && noImprovement < maxNoImprovement
                        && System.nanoTime() < deadline;
                iteration++) {
            List<List<Customer>> base = SolutionSupport.assignmentsFrom(problem, current);
            List<Candidate> neighbors = generateNeighbors(
                    problem,
                    base,
                    started,
                    neighborhoodSize,
                    random);

            Candidate selected = null;
            for (Candidate candidate : neighbors) {
                boolean isTabu = tabuUntil.getOrDefault(candidate.moveKey(), 0) > iteration;
                boolean improvesBest = SolutionSupport.better(candidate.solution(), best);
                if ((!isTabu || (aspiration && improvesBest))
                        && (selected == null
                        || SolutionSupport.better(candidate.solution(), selected.solution()))) {
                    selected = candidate;
                }
            }

            if (selected == null) {
                break;
            }

            current = selected.solution();
            tabuUntil.put(selected.moveKey(), iteration + tenure);
            if (SolutionSupport.better(current, best)) {
                best = current;
                bestIteration = iteration;
                noImprovement = 0;
            } else {
                noImprovement++;
            }

            history.add(snapshot(
                    iteration,
                    started,
                    current,
                    best,
                    neighbors.size(),
                    "tabu=" + tabuUntil.size()));
        }

        if (history.isEmpty()) {
            history.add(snapshot(0, started, initial, best, 1, "Initial solution"));
        }
        return new SolverRun(SolutionSupport.finish(best, started), history, bestIteration);
    }

    private List<Candidate> generateNeighbors(
            ProblemInstance problem,
            List<List<Customer>> base,
            long started,
            int limit,
            Random random) {
        List<Position> positions = new ArrayList<>();
        for (int route = 0; route < base.size(); route++) {
            for (int index = 0; index < base.get(route).size(); index++) {
                positions.add(new Position(route, index));
            }
        }
        // Seed làm thay đổi thứ tự duyệt nhưng kết quả vẫn tái hiện được.
        java.util.Collections.shuffle(positions, random);

        List<Candidate> candidates = new ArrayList<>();
        for (int leftIndex = 0; leftIndex < positions.size() && candidates.size() < limit; leftIndex++) {
            for (int rightIndex = leftIndex + 1;
                    rightIndex < positions.size() && candidates.size() < limit;
                    rightIndex++) {
                Position left = positions.get(leftIndex);
                Position right = positions.get(rightIndex);
                List<List<Customer>> assignments = mutableCopy(base);
                Customer a = assignments.get(left.route()).get(left.index());
                Customer b = assignments.get(right.route()).get(right.index());
                assignments.get(left.route()).set(left.index(), b);
                assignments.get(right.route()).set(right.index(), a);
                addIfFeasible(
                        problem,
                        assignments,
                        started,
                        "SWAP:" + Math.min(a.orderId(), b.orderId()) + ':'
                                + Math.max(a.orderId(), b.orderId()),
                        candidates);
            }
        }

        // 2-opt đảo một đoạn trong cùng route, hữu ích cho giảm khoảng cách.
        for (int route = 0; route < base.size() && candidates.size() < limit; route++) {
            int size = base.get(route).size();
            for (int from = 0; from < size - 1 && candidates.size() < limit; from++) {
                for (int to = from + 1; to < size && candidates.size() < limit; to++) {
                    List<List<Customer>> assignments = mutableCopy(base);
                    java.util.Collections.reverse(assignments.get(route).subList(from, to + 1));
                    addIfFeasible(
                            problem,
                            assignments,
                            started,
                            "2OPT:" + route + ':' + from + ':' + to,
                            candidates);
                }
            }
        }
        return candidates;
    }

    private void addIfFeasible(
            ProblemInstance problem,
            List<List<Customer>> assignments,
            long started,
            String moveKey,
            List<Candidate> candidates) {
        Solution solution = SolutionSupport.buildSolution(problem, assignments, started);
        if (solution.feasible()) {
            candidates.add(new Candidate(solution, moveKey));
        }
    }

    private List<List<Customer>> mutableCopy(List<List<Customer>> source) {
        List<List<Customer>> copy = new ArrayList<>();
        source.forEach(route -> copy.add(new ArrayList<>(route)));
        return copy;
    }

    private IterationSnapshot snapshot(
            int iteration,
            long started,
            Solution current,
            Solution best,
            int feasibleCount,
            String notes) {
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
                null,
                notes);
    }

    private record Position(int route, int index) {}

    private record Candidate(Solution solution, String moveKey) {}
}
