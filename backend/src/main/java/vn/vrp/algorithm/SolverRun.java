package vn.vrp.algorithm;

import java.util.List;
import vn.vrp.model.Solution;

/** Kết quả solver cùng lịch sử hội tụ để lưu vào experiment_iteration_log. */
public record SolverRun(Solution solution, List<IterationSnapshot> iterations, int iterationFound) {
    public SolverRun {
        iterations = iterations == null ? List.of() : List.copyOf(iterations);
    }

    public static SolverRun singleStep(Solution solution) {
        double objective = SolutionSupport.objective(solution);
        IterationSnapshot snapshot = new IterationSnapshot(
                0,
                solution.executionTimeMs(),
                objective,
                objective,
                solution.routes().size(),
                solution.routes().size(),
                solution.totalDistance(),
                solution.totalDistance(),
                solution.feasible() ? 1 : 0,
                null,
                "Constructive solution");
        return new SolverRun(solution, List.of(snapshot), 0);
    }
}
