package vn.vrp.controller;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.vrp.algorithm.AlgorithmRegistry;
import vn.vrp.algorithm.SolverOptions;
import vn.vrp.algorithm.SolverRun;
import vn.vrp.algorithm.VrpSolver;
import vn.vrp.algorithm.greedy.GreedyCvrpSolver;
import vn.vrp.db.DatabaseConfig;
import vn.vrp.db.ExperimentDao;
import vn.vrp.db.ProblemInstanceDao;
import vn.vrp.model.ProblemInstance;
import vn.vrp.model.Vehicle;
import vn.vrp.validator.SolutionValidator;
import vn.vrp.validator.ValidationResult;

/** API chạy, phân tích, so sánh và xóa experiment. */
@RestController
@RequestMapping("/api/experiments")
@CrossOrigin(origins = "*", methods = {
        RequestMethod.GET,
        RequestMethod.POST,
        RequestMethod.DELETE,
        RequestMethod.OPTIONS
})
public class VrpController {

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAllExperiments() {
        String sql = """
                SELECT e.experiment_id, e.experiment_code, e.start_time, e.end_time,
                       e.status, e.random_seed, d.dataset_code,
                       d.customer_count AS dataset_customer_count,
                       a.algorithm_code, a.algorithm_name,
                       r.total_distance, r.total_travel_time,
                       r.total_waiting_time, r.vehicle_used,
                       r.objective_value, r.execution_time_ms, r.is_feasible,
                       r.unserved_order_count,
                       COALESCE((
                           SELECT SUM(
                               v.fixed_cost
                               + rr.total_distance * v.cost_per_km
                               + (rr.total_travel_time + rr.total_waiting_time
                                  + rr.total_service_time) / 60.0 * v.cost_per_minute)
                           FROM route_result rr
                           JOIN vehicle v ON v.vehicle_id = rr.vehicle_id
                           WHERE rr.result_id = r.result_id
                       ), 0) AS total_cost,
                       COALESCE((
                           SELECT COUNT(*)
                           FROM route_result rr
                           JOIN route_stop_result rs ON rs.route_id = rr.route_id
                           WHERE rr.result_id = r.result_id
                             AND rs.stop_type = 'CUSTOMER'
                       ), 0) AS served_customer_count
                FROM experiment e
                JOIN dataset d ON d.dataset_id = e.dataset_id
                JOIN algorithm a ON a.algorithm_id = e.algorithm_id
                LEFT JOIN experiment_result r ON r.experiment_id = e.experiment_id
                ORDER BY e.experiment_id DESC
                """;
        List<Map<String, Object>> experiments = new ArrayList<>();
        try (Connection connection = connect();
                var statement = connection.prepareStatement(sql);
                var result = statement.executeQuery()) {
            while (result.next()) {
                experiments.add(summary(result));
            }
            return ResponseEntity.ok(experiments);
        } catch (Exception exception) {
            exception.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }

    /** Trả các KPI đã chuẩn hóa để FE so sánh nhiều lần chạy. */
    @GetMapping("/compare")
    public ResponseEntity<List<Map<String, Object>>> compareExperiments(
            @RequestParam("ids") List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 50) {
            return ResponseEntity.badRequest().body(List.of());
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql = """
                SELECT e.experiment_id, e.experiment_code, e.start_time, e.end_time,
                       e.status, e.random_seed, d.dataset_code,
                       d.customer_count AS dataset_customer_count,
                       a.algorithm_code, a.algorithm_name,
                       r.total_distance, r.total_travel_time,
                       r.total_waiting_time, r.vehicle_used,
                       r.objective_value, r.execution_time_ms, r.is_feasible,
                       r.unserved_order_count,
                       COALESCE((
                           SELECT SUM(
                               v.fixed_cost
                               + rr.total_distance * v.cost_per_km
                               + (rr.total_travel_time + rr.total_waiting_time
                                  + rr.total_service_time) / 60.0 * v.cost_per_minute)
                           FROM route_result rr
                           JOIN vehicle v ON v.vehicle_id = rr.vehicle_id
                           WHERE rr.result_id = r.result_id
                       ), 0) AS total_cost,
                       COALESCE((
                           SELECT COUNT(*)
                           FROM route_result rr
                           JOIN route_stop_result rs ON rs.route_id = rr.route_id
                           WHERE rr.result_id = r.result_id
                             AND rs.stop_type = 'CUSTOMER'
                       ), 0) AS served_customer_count
                FROM experiment e
                JOIN dataset d ON d.dataset_id = e.dataset_id
                JOIN algorithm a ON a.algorithm_id = e.algorithm_id
                LEFT JOIN experiment_result r ON r.experiment_id = e.experiment_id
                WHERE e.experiment_id IN (%s)
                ORDER BY e.experiment_id
                """.formatted(placeholders);
        List<Map<String, Object>> experiments = new ArrayList<>();
        try (Connection connection = connect();
                var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < ids.size(); index++) {
                statement.setLong(index + 1, ids.get(index));
            }
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    experiments.add(summary(result));
                }
            }
            return ResponseEntity.ok(experiments);
        } catch (Exception exception) {
            exception.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getExperimentDetail(@PathVariable("id") long id) {
        try (Connection connection = connect()) {
            Map<String, Object> response = new LinkedHashMap<>();
            Ids ids = loadExperiment(connection, id, response);
            if (ids == null) {
                return ResponseEntity.notFound().build();
            }
            response.put("depots", loadDepots(connection, ids.datasetId()));
            response.put("customers", loadCustomers(connection, ids.datasetId()));
            List<Map<String, Object>> routes = loadRoutes(connection, ids.resultId());
            response.put("routes", routes);
            addCostSummary(response, routes);
            response.put("parameters", loadParameters(connection, id));
            response.put("iterations", loadIterations(connection, id));
            response.put("validation", loadValidation(connection, ids.resultId()));
            return ResponseEntity.ok(response);
        } catch (Exception exception) {
            exception.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Lỗi đọc experiment: " + exception.getMessage()));
        }
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runExperiment(
            @RequestBody(required = false) Map<String, Object> payload) {
        try {
            Map<String, Object> request = payload == null ? Map.of() : payload;
            long datasetId = longValue(request, "datasetId", 1);
            long seed = longValue(request, "seed", System.currentTimeMillis());
            double capacityOverride = doubleValue(request, "capacityOverride", -1);
            int iterationLimit = intValue(request, "iterationLimit", 100);
            int timeLimitSeconds = intValue(request, "timeLimitSeconds", 0);
            String algorithmCode = stringValue(
                    request,
                    "algorithmCode",
                    GreedyCvrpSolver.CODE);
            Map<String, String> parameters = stringMap(request.get("parameters"));
            SolverOptions options = new SolverOptions(
                    seed,
                    iterationLimit,
                    timeLimitSeconds,
                    parameters);
            VrpSolver solver = new AlgorithmRegistry().require(algorithmCode);

            long experimentId;
            try (Connection connection = connect()) {
                ProblemInstance problem = new ProblemInstanceDao().load(connection, datasetId);
                if (capacityOverride > 0) {
                    problem = withCapacity(problem, capacityOverride);
                }

                SolverRun run = solver.solve(problem, options);
                ValidationResult validation = new SolutionValidator().validate(
                        problem,
                        run.solution());
                experimentId = new ExperimentDao().save(
                        connection,
                        problem,
                        run,
                        validation,
                        options,
                        solver.code());
            }
            return getExperimentDetail(experimentId);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        } catch (Exception exception) {
            exception.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Lỗi thực thi: " + exception.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteExperiment(@PathVariable("id") long id) {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM experiment WHERE experiment_id = ?")) {
            statement.setLong(1, id);
            int affected = statement.executeUpdate();
            if (affected == 0) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of("success", true, "experimentId", id));
        } catch (Exception exception) {
            exception.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", exception.getMessage()));
        }
    }

    private Ids loadExperiment(
            Connection connection,
            long experimentId,
            Map<String, Object> response) throws Exception {
        String sql = """
                SELECT e.experiment_id, e.experiment_code, e.dataset_id,
                       e.objective_type, e.time_limit_seconds, e.iteration_limit,
                       e.random_seed, e.start_time, e.end_time, e.status,
                       d.dataset_code, d.dataset_name, d.dataset_type,
                       a.algorithm_code, a.algorithm_name, a.algorithm_family,
                       r.result_id, r.total_distance, r.total_travel_time,
                       r.total_waiting_time, r.total_service_time, r.vehicle_used,
                       r.unserved_order_count, r.capacity_violation,
                       r.time_window_violation, r.objective_value,
                       r.execution_time_ms, r.iteration_found, r.is_feasible
                FROM experiment e
                JOIN dataset d ON d.dataset_id = e.dataset_id
                JOIN algorithm a ON a.algorithm_id = e.algorithm_id
                JOIN experiment_result r ON r.experiment_id = e.experiment_id
                WHERE e.experiment_id = ?
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, experimentId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                Map<String, Object> experiment = new LinkedHashMap<>();
                experiment.put("EXPERIMENT_ID", result.getLong("experiment_id"));
                experiment.put("EXPERIMENT_CODE", result.getString("experiment_code"));
                experiment.put("DATASET_CODE", result.getString("dataset_code"));
                experiment.put("DATASET_NAME", result.getString("dataset_name"));
                experiment.put("DATASET_TYPE", result.getString("dataset_type"));
                experiment.put("ALGORITHM_CODE", result.getString("algorithm_code"));
                experiment.put("ALGORITHM_NAME", result.getString("algorithm_name"));
                experiment.put("ALGORITHM_FAMILY", result.getString("algorithm_family"));
                experiment.put("OBJECTIVE_TYPE", result.getString("objective_type"));
                experiment.put("RANDOM_SEED", result.getLong("random_seed"));
                experiment.put("START_TIME", result.getString("start_time"));
                experiment.put("END_TIME", result.getString("end_time"));
                experiment.put("STATUS", result.getString("status"));
                experiment.put("TOTAL_DISTANCE", result.getDouble("total_distance"));
                experiment.put("TOTAL_TRAVEL_TIME", result.getInt("total_travel_time"));
                experiment.put("TOTAL_WAITING_TIME", result.getInt("total_waiting_time"));
                experiment.put("TOTAL_SERVICE_TIME", result.getInt("total_service_time"));
                experiment.put("VEHICLE_USED", result.getInt("vehicle_used"));
                experiment.put("UNSERVED_ORDER_COUNT", result.getInt("unserved_order_count"));
                experiment.put("CAPACITY_VIOLATION", result.getDouble("capacity_violation"));
                experiment.put("TIME_WINDOW_VIOLATION", result.getInt("time_window_violation"));
                experiment.put("OBJECTIVE_VALUE", result.getDouble("objective_value"));
                experiment.put("EXECUTION_TIME_MS", result.getLong("execution_time_ms"));
                experiment.put("ITERATION_FOUND", result.getObject("iteration_found"));
                experiment.put("IS_FEASIBLE", result.getBoolean("is_feasible") ? 1 : 0);
                response.put("experiment", experiment);
                return new Ids(result.getLong("dataset_id"), result.getLong("result_id"));
            }
        }
    }

    private List<Map<String, Object>> loadDepots(Connection connection, long datasetId)
            throws Exception {
        String sql = """
                SELECT d.depot_id, d.depot_code, d.depot_name,
                       d.open_time, d.close_time,
                       COALESCE(l.latitude, l.y_coordinate, 0) latitude,
                       COALESCE(l.longitude, l.x_coordinate, 0) longitude
                FROM depot d
                JOIN location l ON l.location_id = d.location_id
                WHERE d.dataset_id = ?
                ORDER BY d.depot_id
                """;
        List<Map<String, Object>> depots = new ArrayList<>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, datasetId);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    Map<String, Object> depot = new LinkedHashMap<>();
                    depot.put("DEPOT_ID", result.getLong("depot_id"));
                    depot.put("DEPOT_CODE", result.getString("depot_code"));
                    depot.put("DEPOT_NAME", result.getString("depot_name"));
                    depot.put("OPEN_TIME", result.getInt("open_time"));
                    depot.put("CLOSE_TIME", result.getInt("close_time"));
                    depot.put("LATITUDE", result.getDouble("latitude"));
                    depot.put("LONGITUDE", result.getDouble("longitude"));
                    depots.add(depot);
                }
            }
        }
        return depots;
    }

    private List<Map<String, Object>> loadCustomers(Connection connection, long datasetId)
            throws Exception {
        String sql = """
                SELECT oi.customer_id, oi.customer_code, oi.customer_name,
                       oi.order_id, oi.order_code, oi.demand_weight,
                       oi.demand_volume, oi.ready_time, oi.due_time,
                       oi.service_time, oi.priority,
                       oi.latitude, oi.longitude, oi.x_coordinate, oi.y_coordinate
                FROM v_order_problem_input oi
                WHERE oi.dataset_id = ? AND oi.status <> 'CANCELLED'
                ORDER BY oi.order_id
                """;
        List<Map<String, Object>> customers = new ArrayList<>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, datasetId);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    Map<String, Object> customer = new LinkedHashMap<>();
                    customer.put("CUSTOMER_ID", result.getLong("customer_id"));
                    customer.put("CUSTOMER_CODE", result.getString("customer_code"));
                    customer.put("CUSTOMER_NAME", result.getString("customer_name"));
                    customer.put("ORDER_ID", result.getLong("order_id"));
                    customer.put("ORDER_CODE", result.getString("order_code"));
                    customer.put("DEMAND_WEIGHT", result.getDouble("demand_weight"));
                    customer.put("DEMAND_VOLUME", result.getDouble("demand_volume"));
                    customer.put("READY_TIME", result.getInt("ready_time"));
                    customer.put("DUE_TIME", result.getInt("due_time"));
                    customer.put("SERVICE_TIME", result.getInt("service_time"));
                    customer.put("PRIORITY", result.getInt("priority"));
                    customer.put("LATITUDE", firstNonNullDouble(
                            result,
                            "latitude",
                            "y_coordinate"));
                    customer.put("LONGITUDE", firstNonNullDouble(
                            result,
                            "longitude",
                            "x_coordinate"));
                    customers.add(customer);
                }
            }
        }
        return customers;
    }

    private List<Map<String, Object>> loadRoutes(Connection connection, long resultId)
            throws Exception {
        String sql = """
                SELECT r.*, v.vehicle_code, v.vehicle_type,
                       v.capacity_weight, v.capacity_volume,
                       v.fixed_cost, v.cost_per_km, v.cost_per_minute
                FROM route_result r
                JOIN vehicle v ON v.vehicle_id = r.vehicle_id
                WHERE r.result_id = ?
                ORDER BY r.route_no
                """;
        List<Map<String, Object>> routes = new ArrayList<>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, resultId);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    long routeId = result.getLong("route_id");
                    Map<String, Object> route = new LinkedHashMap<>();
                    route.put("ROUTE_ID", routeId);
                    route.put("ROUTE_NO", result.getInt("route_no"));
                    route.put("VEHICLE_ID", result.getLong("vehicle_id"));
                    route.put("VEHICLE_CODE", result.getString("vehicle_code"));
                    route.put("VEHICLE_TYPE", result.getString("vehicle_type"));
                    route.put("CAPACITY_WEIGHT", result.getDouble("capacity_weight"));
                    route.put("CAPACITY_VOLUME", result.getDouble("capacity_volume"));
                    route.put("TOTAL_LOAD", result.getDouble("total_load_weight"));
                    route.put("TOTAL_LOAD_VOLUME", result.getDouble("total_load_volume"));
                    route.put("TOTAL_DISTANCE", result.getDouble("total_distance"));
                    route.put("TOTAL_TRAVEL_TIME", result.getInt("total_travel_time"));
                    route.put("TOTAL_WAITING_TIME", result.getInt("total_waiting_time"));
                    route.put("TOTAL_SERVICE_TIME", result.getInt("total_service_time"));
                    double fixedCost = result.getDouble("fixed_cost");
                    double distanceCost = result.getDouble("total_distance")
                            * result.getDouble("cost_per_km");
                    double timeCost = (result.getInt("total_travel_time")
                            + result.getInt("total_waiting_time")
                            + result.getInt("total_service_time"))
                            / 60.0 * result.getDouble("cost_per_minute");
                    route.put("FIXED_COST_RATE", fixedCost);
                    route.put("COST_PER_KM", result.getDouble("cost_per_km"));
                    route.put("COST_PER_MINUTE", result.getDouble("cost_per_minute"));
                    route.put("FIXED_COST", roundCost(fixedCost));
                    route.put("DISTANCE_COST", roundCost(distanceCost));
                    route.put("TIME_COST", roundCost(timeCost));
                    double totalCost = fixedCost + distanceCost + timeCost;
                    route.put("TOTAL_COST", roundCost(totalCost));
                    route.put("START_TIME", result.getInt("start_time"));
                    route.put("END_TIME", result.getInt("end_time"));
                    route.put("IS_FEASIBLE", result.getBoolean("is_feasible") ? 1 : 0);
                    List<Map<String, Object>> stops = loadStops(connection, routeId);
                    long customerStopCount = stops.stream()
                            .filter(stop -> "CUSTOMER".equals(stop.get("STOP_TYPE")))
                            .count();
                    route.put("CUSTOMER_STOP_COUNT", customerStopCount);
                    route.put("COST_PER_CUSTOMER", customerStopCount == 0
                            ? 0
                            : roundCost(totalCost / customerStopCount));
                    route.put("stops", stops);
                    routes.add(route);
                }
            }
        }
        return routes;
    }

    private List<Map<String, Object>> loadStops(Connection connection, long routeId)
            throws Exception {
        String sql = """
                SELECT s.*,
                       COALESCE(l.latitude, l.y_coordinate, 0) latitude,
                       COALESCE(l.longitude, l.x_coordinate, 0) longitude,
                       c.customer_code, d.depot_code
                FROM route_stop_result s
                JOIN location l ON l.location_id = s.location_id
                LEFT JOIN customer c ON c.location_id = s.location_id
                LEFT JOIN depot d ON d.location_id = s.location_id
                WHERE s.route_id = ?
                ORDER BY s.sequence_no
                """;
        List<Map<String, Object>> stops = new ArrayList<>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, routeId);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    Map<String, Object> stop = new LinkedHashMap<>();
                    stop.put("SEQUENCE_NO", result.getInt("sequence_no"));
                    stop.put("STOP_TYPE", result.getString("stop_type"));
                    stop.put("LOCATION_ID", result.getLong("location_id"));
                    stop.put("ORDER_ID", result.getObject("order_id"));
                    stop.put("ARRIVAL_TIME", result.getInt("arrival_time"));
                    stop.put("SERVICE_START_TIME", result.getInt("service_start_time"));
                    stop.put("DEPARTURE_TIME", result.getInt("departure_time"));
                    stop.put("WAITING_TIME", result.getInt("waiting_time"));
                    stop.put("SERVICE_TIME", result.getInt("service_time"));
                    stop.put("DEMAND_WEIGHT", result.getDouble("demand_weight"));
                    stop.put("LOAD_BEFORE_WEIGHT", result.getDouble("load_before_weight"));
                    stop.put("LOAD_AFTER_WEIGHT", result.getDouble("load_after_weight"));
                    stop.put("TRAVEL_DISTANCE", result.getDouble("travel_distance"));
                    stop.put("TRAVEL_TIME", result.getInt("travel_time"));
                    stop.put("CAPACITY_VIOLATION", result.getDouble("capacity_violation"));
                    stop.put("TIME_WINDOW_VIOLATION", result.getInt("time_window_violation"));
                    stop.put("LATITUDE", result.getDouble("latitude"));
                    stop.put("LONGITUDE", result.getDouble("longitude"));
                    String customerCode = result.getString("customer_code");
                    String depotCode = result.getString("depot_code");
                    stop.put("CODE", customerCode != null
                            ? customerCode
                            : depotCode != null ? depotCode : "LOC_" + result.getLong("location_id"));
                    stops.add(stop);
                }
            }
        }
        return stops;
    }

    private Map<String, String> loadParameters(Connection connection, long experimentId)
            throws Exception {
        String sql = """
                SELECT p.parameter_code, v.parameter_value
                FROM experiment_parameter_value v
                JOIN algorithm_parameter p ON p.parameter_id = v.parameter_id
                WHERE v.experiment_id = ?
                ORDER BY p.parameter_id
                """;
        Map<String, String> parameters = new LinkedHashMap<>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, experimentId);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    parameters.put(
                            result.getString("parameter_code"),
                            result.getString("parameter_value"));
                }
            }
        }
        return parameters;
    }

    private List<Map<String, Object>> loadIterations(Connection connection, long experimentId)
            throws Exception {
        String sql = """
                SELECT iteration_no, elapsed_time_ms, current_objective,
                       best_objective, current_vehicle_used, best_vehicle_used,
                       current_distance, best_distance,
                       feasible_solution_count, diversity_value, notes
                FROM experiment_iteration_log
                WHERE experiment_id = ?
                ORDER BY iteration_no
                """;
        List<Map<String, Object>> iterations = new ArrayList<>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, experimentId);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("ITERATION_NO", result.getInt("iteration_no"));
                    row.put("ELAPSED_TIME_MS", result.getLong("elapsed_time_ms"));
                    row.put("CURRENT_OBJECTIVE", result.getObject("current_objective"));
                    row.put("BEST_OBJECTIVE", result.getObject("best_objective"));
                    row.put("CURRENT_VEHICLE_USED", result.getObject("current_vehicle_used"));
                    row.put("BEST_VEHICLE_USED", result.getObject("best_vehicle_used"));
                    row.put("CURRENT_DISTANCE", result.getObject("current_distance"));
                    row.put("BEST_DISTANCE", result.getObject("best_distance"));
                    row.put("FEASIBLE_SOLUTION_COUNT", result.getInt("feasible_solution_count"));
                    row.put("DIVERSITY_VALUE", result.getObject("diversity_value"));
                    row.put("NOTES", result.getString("notes"));
                    iterations.add(row);
                }
            }
        }
        return iterations;
    }

    private Map<String, Object> loadValidation(Connection connection, long resultId)
            throws Exception {
        String sql = """
                SELECT coverage_valid, capacity_valid, time_window_valid,
                       structure_valid, calculated_feasible, stored_feasible
                FROM v_result_validation
                WHERE result_id = ?
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, resultId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return Map.of();
                }
                Map<String, Object> validation = new LinkedHashMap<>();
                validation.put("COVERAGE_VALID", result.getInt("coverage_valid"));
                validation.put("CAPACITY_VALID", result.getInt("capacity_valid"));
                validation.put("TIME_WINDOW_VALID", result.getInt("time_window_valid"));
                validation.put("STRUCTURE_VALID", result.getInt("structure_valid"));
                validation.put("CALCULATED_FEASIBLE", result.getInt("calculated_feasible"));
                validation.put("STORED_FEASIBLE", result.getInt("stored_feasible"));
                return validation;
            }
        }
    }

    private Map<String, Object> summary(ResultSet result) throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("EXPERIMENT_ID", result.getLong("experiment_id"));
        map.put("EXPERIMENT_CODE", result.getString("experiment_code"));
        map.put("DATASET_CODE", result.getString("dataset_code"));
        map.put("ALGORITHM_CODE", result.getString("algorithm_code"));
        map.put("ALGORITHM_NAME", result.getString("algorithm_name"));
        map.put("START_TIME", result.getString("start_time"));
        map.put("END_TIME", result.getString("end_time"));
        map.put("STATUS", result.getString("status"));
        map.put("RANDOM_SEED", result.getLong("random_seed"));
        map.put("TOTAL_DISTANCE", result.getObject("total_distance"));
        map.put("TOTAL_TRAVEL_TIME", result.getObject("total_travel_time"));
        map.put("TOTAL_WAITING_TIME", result.getObject("total_waiting_time"));
        map.put("VEHICLE_USED", result.getObject("vehicle_used"));
        map.put("OBJECTIVE_VALUE", result.getObject("objective_value"));
        Object totalCostValue = result.getObject("total_cost");
        long servedCustomerCount = result.getLong("served_customer_count");
        map.put("TOTAL_COST", totalCostValue);
        map.put("DATASET_CUSTOMER_COUNT", result.getInt("dataset_customer_count"));
        map.put("UNSERVED_ORDER_COUNT", result.getObject("unserved_order_count"));
        map.put("SERVED_CUSTOMER_COUNT", servedCustomerCount);
        map.put("COST_PER_CUSTOMER", servedCustomerCount == 0
                ? 0
                : roundCost(number(totalCostValue) / servedCustomerCount));
        map.put("EXECUTION_TIME_MS", result.getObject("execution_time_ms"));
        map.put("IS_FEASIBLE", result.getObject("is_feasible") == null
                ? null
                : result.getBoolean("is_feasible") ? 1 : 0);
        return map;
    }

    /** Tổng hợp chi phí từ các route để cả dữ liệu cũ cũng hiển thị được mà không cần migration. */
    @SuppressWarnings("unchecked")
    private void addCostSummary(
            Map<String, Object> response,
            List<Map<String, Object>> routes) {
        double fixedCost = routes.stream()
                .mapToDouble(route -> number(route.get("FIXED_COST")))
                .sum();
        double distanceCost = routes.stream()
                .mapToDouble(route -> number(route.get("DISTANCE_COST")))
                .sum();
        double timeCost = routes.stream()
                .mapToDouble(route -> number(route.get("TIME_COST")))
                .sum();
        long servedCustomerCount = routes.stream()
                .mapToLong(route -> ((Number) route.getOrDefault(
                        "CUSTOMER_STOP_COUNT",
                        0)).longValue())
                .sum();
        double totalCost = fixedCost + distanceCost + timeCost;
        Object value = response.get("experiment");
        if (value instanceof Map<?, ?> rawExperiment) {
            Map<String, Object> experiment = (Map<String, Object>) rawExperiment;
            experiment.put("FIXED_COST", roundCost(fixedCost));
            experiment.put("DISTANCE_COST", roundCost(distanceCost));
            experiment.put("TIME_COST", roundCost(timeCost));
            experiment.put("TOTAL_COST", roundCost(totalCost));
            experiment.put("SERVED_CUSTOMER_COUNT", servedCustomerCount);
            experiment.put("COST_PER_CUSTOMER", servedCustomerCount == 0
                    ? 0
                    : roundCost(totalCost / servedCustomerCount));
        }
    }

    private double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private double roundCost(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }

    private ProblemInstance withCapacity(ProblemInstance problem, double capacity) {
        List<Vehicle> vehicles = problem.vehicles().stream()
                .map(vehicle -> new Vehicle(
                        vehicle.id(),
                        vehicle.code(),
                        vehicle.type(),
                        capacity,
                        vehicle.capacityVolume(),
                        vehicle.startDepotId(),
                        vehicle.endDepotId(),
                        vehicle.availableFrom(),
                        vehicle.availableTo(),
                        vehicle.fixedCost(),
                        vehicle.costPerKm(),
                        vehicle.costPerMinute()))
                .toList();
        return new ProblemInstance(
                problem.datasetId(),
                problem.code(),
                problem.type(),
                problem.depot(),
                problem.customers(),
                vehicles,
                problem.arcs());
    }

    private Connection connect() throws Exception {
        return DatabaseConfig.from(DatabaseConfig.loadProperties()).connect();
    }

    private static String stringValue(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        return value == null ? fallback : value.toString();
    }

    private static long longValue(Map<String, Object> values, String key, long fallback) {
        Object value = values.get(key);
        return value == null || value.toString().isBlank()
                ? fallback
                : Long.parseLong(value.toString());
    }

    private static int intValue(Map<String, Object> values, String key, int fallback) {
        Object value = values.get(key);
        return value == null || value.toString().isBlank()
                ? fallback
                : Integer.parseInt(value.toString());
    }

    private static double doubleValue(Map<String, Object> values, String key, double fallback) {
        Object value = values.get(key);
        return value == null || value.toString().isBlank()
                ? fallback
                : Double.parseDouble(value.toString());
    }

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key != null && item != null) {
                result.put(key.toString(), item.toString());
            }
        });
        return result;
    }

    private double firstNonNullDouble(ResultSet result, String first, String second)
            throws Exception {
        double value = result.getDouble(first);
        if (!result.wasNull()) {
            return value;
        }
        value = result.getDouble(second);
        return result.wasNull() ? 0 : value;
    }

    private record Ids(long datasetId, long resultId) {}
}
