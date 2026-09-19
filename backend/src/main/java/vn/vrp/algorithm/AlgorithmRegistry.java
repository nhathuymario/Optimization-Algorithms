package vn.vrp.algorithm;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import vn.vrp.algorithm.aco.AntColonySolver;
import vn.vrp.algorithm.ga.GeneticAlgorithmSolver;
import vn.vrp.algorithm.greedy.GreedyCvrpSolver;
import vn.vrp.algorithm.tabu.TabuSearchSolver;

/** Registry duy nhất ánh xạ algorithm_code trong database sang implementation Java. */
public final class AlgorithmRegistry {
    private final Map<String, VrpSolver> solvers = new LinkedHashMap<>();

    public AlgorithmRegistry() {
        register(new GreedyCvrpSolver());
        register(new TabuSearchSolver());
        register(new GeneticAlgorithmSolver());
        register(new AntColonySolver());
    }

    private void register(VrpSolver solver) {
        solvers.put(solver.code().toUpperCase(Locale.ROOT), solver);
    }

    public VrpSolver require(String code) {
        String normalized = code == null || code.isBlank()
                ? GreedyCvrpSolver.CODE
                : code.toUpperCase(Locale.ROOT);
        VrpSolver solver = solvers.get(normalized);
        if (solver == null) {
            throw new IllegalArgumentException(
                    "Thuật toán chưa được hỗ trợ: " + code + ". Hỗ trợ: " + solvers.keySet());
        }
        return solver;
    }

    public boolean supports(String code) {
        return code != null && solvers.containsKey(code.toUpperCase(Locale.ROOT));
    }
}
