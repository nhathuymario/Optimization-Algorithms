package vn.vrp.algorithm.greedy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import vn.vrp.algorithm.SolutionSupport;
import vn.vrp.algorithm.SolverOptions;
import vn.vrp.algorithm.SolverRun;
import vn.vrp.algorithm.VrpSolver;
import vn.vrp.model.Customer;
import vn.vrp.model.ProblemInstance;
import vn.vrp.model.Solution;
import vn.vrp.model.Vehicle;

/**
 * Baseline nearest-neighbor có kiểm tra capacity, vehicle type và time window.
 * Thuật toán nhanh, xác định và phù hợp để tạo nghiệm đầu cho metaheuristic.
 */
public final class GreedyCvrpSolver implements VrpSolver {
    public static final String CODE = "BASELINE_SEQUENTIAL_INSERTION";

    @Override
    public String code() {
        return CODE;
    }

    /** API tương thích với test/code cũ. */
    public Solution solve(ProblemInstance instance) {
        return solve(instance, new SolverOptions(0, 1, 0, null)).solution();
    }

    @Override
    public SolverRun solve(ProblemInstance instance, SolverOptions options) {
        long started = System.nanoTime();
        Set<Customer> remaining = new LinkedHashSet<>(instance.customers());
        List<List<Customer>> assignments = SolutionSupport.emptyAssignments(instance);

        for (int vehicleIndex = 0; vehicleIndex < instance.vehicles().size(); vehicleIndex++) {
            Vehicle vehicle = instance.vehicles().get(vehicleIndex);
            List<Customer> selected = assignments.get(vehicleIndex);
            long currentLocation = instance.depot().locationId();

            while (!remaining.isEmpty()) {
                final long from = currentLocation;
                Customer next = remaining.stream()
                        .filter(customer -> SolutionSupport.canAppend(
                                instance,
                                vehicle,
                                selected,
                                customer))
                        .min(Comparator
                                .comparingDouble((Customer customer) ->
                                        instance.arc(from, customer.locationId()).distance())
                                // Due time là tiêu chí phụ khi hai điểm cách đều nhau.
                                .thenComparingInt(Customer::dueTime)
                                .thenComparing(Comparator.comparingInt(Customer::priority).reversed())
                                .thenComparingLong(Customer::orderId))
                        .orElse(null);

                if (next == null) {
                    break;
                }
                selected.add(next);
                remaining.remove(next);
                currentLocation = next.locationId();
            }

            if (remaining.isEmpty()) {
                break;
            }
        }

        if (!remaining.isEmpty()) {
            List<Long> unserved = remaining.stream().map(Customer::orderId).toList();
            throw new IllegalStateException(
                    "Không tìm được route khả thi cho " + remaining.size()
                            + " đơn hàng: " + unserved);
        }

        Solution solution = SolutionSupport.buildSolution(instance, assignments, started);
        return SolverRun.singleStep(solution);
    }

    /**
     * Tạo một permutation khả thi theo baseline; được GA/ACO dùng làm chromosome mồi.
     */
    public List<Customer> seedPermutation(ProblemInstance instance) {
        Solution solution = solve(instance);
        List<Customer> permutation = new ArrayList<>();
        solution.routes().forEach(route -> permutation.addAll(route.customers()));
        return permutation;
    }
}
