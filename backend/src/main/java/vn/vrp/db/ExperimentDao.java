package vn.vrp.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Locale;
import java.util.Map;
import vn.vrp.algorithm.IterationSnapshot;
import vn.vrp.algorithm.SolutionSupport;
import vn.vrp.algorithm.SolverOptions;
import vn.vrp.algorithm.SolverRun;
import vn.vrp.algorithm.greedy.GreedyCvrpSolver;
import vn.vrp.model.ProblemInstance;
import vn.vrp.model.Route;
import vn.vrp.model.RouteStop;
import vn.vrp.model.Solution;
import vn.vrp.validator.ValidationResult;

/** Lưu nguyên tử experiment, parameter, convergence log và cây kết quả route/stop. */
public final class ExperimentDao {

    public long save(
            Connection connection,
            ProblemInstance problem,
            SolverRun run,
            ValidationResult validation,
            SolverOptions options,
            String algorithmCode) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            long algorithmId = findAlgorithm(connection, algorithmCode);
            long experimentId = insertExperiment(
                    connection,
                    problem,
                    options,
                    algorithmId,
                    algorithmCode);
            insertParameterValues(connection, experimentId, algorithmId, options.parameters());
            long resultId = insertResult(connection, experimentId, run, validation);

            int routeNo = 1;
            for (Route route : run.solution().routes()) {
                insertRoute(connection, resultId, routeNo++, route);
            }
            for (IterationSnapshot snapshot : run.iterations()) {
                insertIteration(connection, experimentId, snapshot);
            }
            try (var statement = connection.prepareStatement(
                    "UPDATE experiment SET end_time = NOW(), status = 'COMPLETED' "
                            + "WHERE experiment_id = ?")) {
                statement.setLong(1, experimentId);
                statement.executeUpdate();
            }

            connection.commit();
            return experimentId;
        } catch (Exception exception) {
            connection.rollback();
            if (exception instanceof SQLException sqlException) {
                throw sqlException;
            }
            throw new SQLException(exception);
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    /** Tương thích với caller cũ của baseline. */
    public long save(
            Connection connection,
            ProblemInstance problem,
            Solution solution,
            ValidationResult validation,
            long seed) throws SQLException {
        return save(
                connection,
                problem,
                SolverRun.singleStep(solution),
                validation,
                new SolverOptions(seed, 1, 0, Map.of()),
                GreedyCvrpSolver.CODE);
    }

    private long findAlgorithm(Connection connection, String algorithmCode) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT algorithm_id FROM algorithm "
                        + "WHERE algorithm_code = ? AND is_active = 1")) {
            statement.setString(1, algorithmCode);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Thiếu algorithm active: " + algorithmCode);
                }
                return result.getLong(1);
            }
        }
    }

    private long insertExperiment(
            Connection connection,
            ProblemInstance problem,
            SolverOptions options,
            long algorithmId,
            String algorithmCode) throws SQLException {
        String safeCode = algorithmCode.replaceAll("[^A-Za-z0-9]+", "_");
        String experimentCode = "EXP_" + safeCode + '_' + problem.datasetId()
                + '_' + System.currentTimeMillis();
        String sql = """
                INSERT INTO experiment(
                    experiment_code, dataset_id, algorithm_id, objective_type,
                    time_limit_seconds, iteration_limit, random_seed,
                    machine_info, start_time, status, notes)
                VALUES (?, ?, ?, 'LEXICOGRAPHIC_VEHICLE_DISTANCE', ?, ?, ?, ?, NOW(), 'RUNNING', ?)
                """;
        try (var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, experimentCode);
            statement.setLong(2, problem.datasetId());
            statement.setLong(3, algorithmId);
            if (options.timeLimitSeconds() <= 0) {
                statement.setNull(4, Types.INTEGER);
            } else {
                statement.setInt(4, options.timeLimitSeconds());
            }
            statement.setInt(5, options.iterationLimit());
            statement.setLong(6, options.seed());
            statement.setString(
                    7,
                    System.getProperty("os.name") + "; Java " + System.getProperty("java.version"));
            statement.setString(8, "Solver " + algorithmCode + " chạy từ REST API");
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    /** Lưu cả default value để mỗi experiment có thể tái hiện độc lập. */
    private void insertParameterValues(
            Connection connection,
            long experimentId,
            long algorithmId,
            Map<String, String> supplied) throws SQLException {
        String query = """
                SELECT parameter_id, parameter_code, default_value
                FROM algorithm_parameter
                WHERE algorithm_id = ?
                ORDER BY parameter_id
                """;
        try (var find = connection.prepareStatement(query)) {
            find.setLong(1, algorithmId);
            try (var result = find.executeQuery();
                    var insert = connection.prepareStatement("""
                            INSERT INTO experiment_parameter_value(
                                experiment_id, parameter_id, parameter_value)
                            VALUES (?, ?, ?)
                            """)) {
                while (result.next()) {
                    String code = result.getString("parameter_code");
                    String value = lookupIgnoreCase(supplied, code);
                    if (value == null) {
                        value = result.getString("default_value");
                    }
                    if (value != null) {
                        insert.setLong(1, experimentId);
                        insert.setLong(2, result.getLong("parameter_id"));
                        insert.setString(3, value);
                        insert.addBatch();
                    }
                }
                insert.executeBatch();
            }
        }
    }

    private String lookupIgnoreCase(Map<String, String> values, String key) {
        String normalized = key.toUpperCase(Locale.ROOT);
        return values.entrySet().stream()
                .filter(entry -> entry.getKey().toUpperCase(Locale.ROOT).equals(normalized))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private long insertResult(
            Connection connection,
            long experimentId,
            SolverRun run,
            ValidationResult validation) throws SQLException {
        Solution solution = run.solution();
        String sql = """
                INSERT INTO experiment_result(
                    experiment_id, total_distance, total_travel_time,
                    total_waiting_time, total_service_time, vehicle_used,
                    unserved_order_count, capacity_violation,
                    time_window_violation, objective_value,
                    execution_time_ms, iteration_found, is_feasible)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            int index = 1;
            statement.setLong(index++, experimentId);
            statement.setDouble(index++, solution.totalDistance());
            statement.setInt(index++, solution.totalTravelTime());
            statement.setInt(index++, solution.totalWaitingTime());
            statement.setInt(index++, solution.totalServiceTime());
            statement.setInt(index++, solution.routes().size());
            statement.setInt(index++, validation.unservedOrders());
            statement.setDouble(index++, validation.capacityViolation());
            statement.setInt(index++, validation.timeWindowViolation());
            statement.setDouble(index++, SolutionSupport.objective(solution));
            statement.setLong(index++, solution.executionTimeMs());
            statement.setInt(index++, run.iterationFound());
            statement.setBoolean(index, validation.valid());
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    private void insertIteration(
            Connection connection,
            long experimentId,
            IterationSnapshot snapshot) throws SQLException {
        String sql = """
                INSERT INTO experiment_iteration_log(
                    experiment_id, iteration_no, elapsed_time_ms,
                    current_objective, best_objective,
                    current_vehicle_used, best_vehicle_used,
                    current_distance, best_distance,
                    feasible_solution_count, diversity_value, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (var statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setLong(index++, experimentId);
            statement.setInt(index++, snapshot.iteration());
            statement.setLong(index++, snapshot.elapsedTimeMs());
            statement.setDouble(index++, snapshot.currentObjective());
            statement.setDouble(index++, snapshot.bestObjective());
            statement.setInt(index++, snapshot.currentVehicleUsed());
            statement.setInt(index++, snapshot.bestVehicleUsed());
            statement.setDouble(index++, snapshot.currentDistance());
            statement.setDouble(index++, snapshot.bestDistance());
            statement.setInt(index++, snapshot.feasibleSolutionCount());
            if (snapshot.diversityValue() == null) {
                statement.setNull(index++, Types.DECIMAL);
            } else {
                statement.setDouble(index++, snapshot.diversityValue());
            }
            statement.setString(index, snapshot.notes());
            statement.executeUpdate();
        }
    }

    private void insertRoute(
            Connection connection,
            long resultId,
            int routeNo,
            Route route) throws SQLException {
        String sql = """
                INSERT INTO route_result(
                    result_id, vehicle_id, route_no, total_distance,
                    total_travel_time, total_waiting_time, total_service_time,
                    total_load_weight, total_load_volume,
                    start_time, end_time, is_feasible)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        long routeId;
        try (var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            int index = 1;
            statement.setLong(index++, resultId);
            statement.setLong(index++, route.vehicle().id());
            statement.setInt(index++, routeNo);
            statement.setDouble(index++, route.totalDistance());
            statement.setInt(index++, route.totalTravelTime());
            statement.setInt(index++, route.totalWaitingTime());
            statement.setInt(index++, route.totalServiceTime());
            statement.setDouble(index++, route.totalLoadWeight());
            statement.setDouble(index++, route.totalLoadVolume());
            statement.setInt(index++, route.startTime());
            statement.setInt(index++, route.endTime());
            statement.setBoolean(index, route.feasible());
            statement.executeUpdate();
            routeId = generatedKey(statement);
        }

        int sequence = 0;
        for (RouteStop stop : route.stops()) {
            insertStop(connection, routeId, sequence++, stop);
        }
    }

    private void insertStop(
            Connection connection,
            long routeId,
            int sequence,
            RouteStop stop) throws SQLException {
        String sql = """
                INSERT INTO route_stop_result(
                    route_id, sequence_no, stop_type, location_id, order_id,
                    arrival_time, service_start_time, departure_time,
                    waiting_time, service_time, demand_weight, demand_volume,
                    load_before_weight, load_after_weight,
                    load_before_volume, load_after_volume,
                    travel_distance, travel_time,
                    capacity_violation, time_window_violation)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (var statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setLong(index++, routeId);
            statement.setInt(index++, sequence);
            statement.setString(index++, stop.type().name());
            statement.setLong(index++, stop.locationId());
            if (stop.orderId() == null) {
                statement.setNull(index++, Types.BIGINT);
            } else {
                statement.setLong(index++, stop.orderId());
            }
            statement.setInt(index++, stop.arrivalTime());
            statement.setInt(index++, stop.serviceStartTime());
            statement.setInt(index++, stop.departureTime());
            statement.setInt(index++, stop.waitingTime());
            statement.setInt(index++, stop.serviceTime());
            statement.setDouble(index++, stop.demandWeight());
            statement.setDouble(index++, stop.demandVolume());
            statement.setDouble(index++, stop.loadBeforeWeight());
            statement.setDouble(index++, stop.loadAfterWeight());
            statement.setDouble(index++, stop.loadBeforeVolume());
            statement.setDouble(index++, stop.loadAfterVolume());
            statement.setDouble(index++, stop.travelDistance());
            statement.setInt(index++, stop.travelTime());
            statement.setDouble(index++, stop.capacityViolation());
            statement.setInt(index, stop.timeWindowViolation());
            statement.executeUpdate();
        }
    }

    private long generatedKey(PreparedStatement statement) throws SQLException {
        try (var result = statement.getGeneratedKeys()) {
            if (!result.next()) {
                throw new SQLException("Không nhận được generated key");
            }
            return result.getLong(1);
        }
    }
}
