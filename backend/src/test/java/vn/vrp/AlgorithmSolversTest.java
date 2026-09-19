package vn.vrp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.vrp.algorithm.AlgorithmRegistry;
import vn.vrp.algorithm.SolverOptions;
import vn.vrp.model.Customer;
import vn.vrp.model.Depot;
import vn.vrp.model.ProblemInstance;
import vn.vrp.model.Solution;
import vn.vrp.model.TravelArc;
import vn.vrp.model.TravelTimeBand;
import vn.vrp.model.Vehicle;
import vn.vrp.validator.SolutionValidator;

class AlgorithmSolversTest {

    @Test
    void allRegisteredAlgorithmsReturnValidSolutions() {
        ProblemInstance problem = fixture();
        AlgorithmRegistry registry = new AlgorithmRegistry();
        Map<String, Map<String, String>> parameters = Map.of(
                "BASELINE_SEQUENTIAL_INSERTION", Map.of(),
                "TABU_ROUTE", Map.of("NEIGHBORHOOD_SIZE", "20", "MAX_NO_IMPROVEMENT", "5"),
                "GA_GIANT_TOUR_SPLIT", Map.of("POPULATION_SIZE", "12", "ELITE_SIZE", "2"),
                "ACO_MACS_VRPTW", Map.of("ANT_COUNT", "8"));

        for (var entry : parameters.entrySet()) {
            var run = registry.require(entry.getKey()).solve(
                    problem,
                    new SolverOptions(20260917L, 5, 0, entry.getValue()));
            var validation = new SolutionValidator().validate(problem, run.solution());
            assertTrue(validation.valid(), () -> entry.getKey() + ": " + validation.errors());
            assertFalse(run.iterations().isEmpty(), entry.getKey());
        }
    }

    @Test
    void timeDependentArcSelectsProfileByDepartureTime() {
        TravelArc arc = new TravelArc(
                10,
                600,
                List.of(new TravelTimeBand(25_200, 32_400, 1_000, 0.6, "CONGESTED")));
        assertEquals(600, arc.travelTimeAt(20_000));
        assertEquals(1_000, arc.travelTimeAt(28_800));
        assertEquals(600, arc.travelTimeAt(40_000));
    }

    @Test
    void validatorDetectsTamperedAggregate() {
        ProblemInstance problem = fixture();
        Solution original = new AlgorithmRegistry()
                .require("BASELINE_SEQUENTIAL_INSERTION")
                .solve(problem, new SolverOptions(1, 1, 0, Map.of()))
                .solution();
        Solution tampered = new Solution(
                original.routes(),
                original.totalDistance() + 1,
                original.totalTravelTime(),
                original.totalWaitingTime(),
                original.totalServiceTime(),
                original.totalCost(),
                original.executionTimeMs(),
                original.feasible());
        var validation = new SolutionValidator().validate(problem, tampered);
        assertFalse(validation.valid());
        assertTrue(validation.errors().stream().anyMatch(error -> error.contains("Tổng khoảng cách")));
    }

    private ProblemInstance fixture() {
        Depot depot = new Depot(1, 1, "D0", 28_800, 43_200);
        List<Customer> customers = List.of(
                customer(1, 2, 10, 28_800, 32_400),
                customer(2, 3, 12, 30_000, 34_200),
                customer(3, 4, 8, 30_600, 36_000),
                customer(4, 5, 15, 29_400, 33_000),
                customer(5, 6, 5, 32_400, 36_000));
        List<Vehicle> vehicles = List.of(vehicle(1), vehicle(2));
        double[][] coordinates = {{0, 0}, {1, 0}, {2, 0}, {3, 0}, {0, 1}, {0, 2}};
        Map<Long, Map<Long, TravelArc>> arcs = new HashMap<>();
        for (int from = 0; from < coordinates.length; from++) {
            for (int to = 0; to < coordinates.length; to++) {
                if (from == to) continue;
                double distance = (Math.abs(coordinates[from][0] - coordinates[to][0])
                        + Math.abs(coordinates[from][1] - coordinates[to][1])) * 10;
                arcs.computeIfAbsent((long) from + 1, ignored -> new HashMap<>())
                        .put((long) to + 1, new TravelArc(distance, (int) distance * 120));
            }
        }
        return new ProblemInstance(1, "TEST", "VRPTW", depot, customers, vehicles, arcs);
    }

    private Customer customer(long id, long location, double demand, int ready, int due) {
        return new Customer(id, id, location, "O" + id, demand, 0, 600, ready, due);
    }

    private Vehicle vehicle(long id) {
        return new Vehicle(
                id,
                "V" + id,
                "STANDARD",
                30,
                0,
                1,
                1,
                28_800,
                43_200,
                100,
                1,
                0);
    }
}
