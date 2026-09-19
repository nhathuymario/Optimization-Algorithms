package vn.vrp.algorithm;

import vn.vrp.model.ProblemInstance;

/** Hợp đồng chung cho mọi thuật toán có thể được chọn từ API/database. */
public interface VrpSolver {
    String code();

    SolverRun solve(ProblemInstance problem, SolverOptions options);
}
