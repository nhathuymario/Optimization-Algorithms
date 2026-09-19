package vn.vrp.controller;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import vn.vrp.db.DatabaseConfig;

/** API quản trị và sinh dataset CVRP/VRPTW/TDVRPTW. */
@RestController
@RequestMapping("/api/datasets")
@CrossOrigin(origins = "*", methods = {
        RequestMethod.GET,
        RequestMethod.POST,
        RequestMethod.DELETE,
        RequestMethod.OPTIONS
})
public class DatasetController {

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAllDatasets() {
        String sql = """
                SELECT dataset_id, dataset_code, dataset_name, dataset_type,
                       customer_count, vehicle_count, depot_count,
                       distance_type, time_dependent, created_at
                FROM dataset
                ORDER BY dataset_id DESC
                """;
        List<Map<String, Object>> datasets = new ArrayList<>();
        try (Connection connection = connect();
                var statement = connection.prepareStatement(sql);
                var result = statement.executeQuery()) {
            while (result.next()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("DATASET_ID", result.getLong("dataset_id"));
                item.put("DATASET_CODE", result.getString("dataset_code"));
                item.put("DATASET_NAME", result.getString("dataset_name"));
                item.put("DATASET_TYPE", result.getString("dataset_type"));
                item.put("CUSTOMER_COUNT", result.getInt("customer_count"));
                item.put("VEHICLE_COUNT", result.getInt("vehicle_count"));
                item.put("DEPOT_COUNT", result.getInt("depot_count"));
                item.put("DISTANCE_TYPE", result.getString("distance_type"));
                item.put("TIME_DEPENDENT", result.getBoolean("time_dependent") ? 1 : 0);
                item.put("CREATED_AT", result.getString("created_at"));
                datasets.add(item);
            }
            return ResponseEntity.ok(datasets);
        } catch (Exception exception) {
            exception.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }

    /** Trả order và vehicle để FE kiểm tra dataset trước khi chạy. */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getDatasetDetail(@PathVariable("id") long id) {
        Map<String, Object> response = new LinkedHashMap<>();
        try (Connection connection = connect()) {
            try (var statement = connection.prepareStatement(
                    "SELECT * FROM dataset WHERE dataset_id = ?")) {
                statement.setLong(1, id);
                try (var result = statement.executeQuery()) {
                    if (!result.next()) {
                        return ResponseEntity.notFound().build();
                    }
                    Map<String, Object> dataset = new LinkedHashMap<>();
                    dataset.put("DATASET_ID", result.getLong("dataset_id"));
                    dataset.put("DATASET_CODE", result.getString("dataset_code"));
                    dataset.put("DATASET_NAME", result.getString("dataset_name"));
                    dataset.put("DATASET_TYPE", result.getString("dataset_type"));
                    dataset.put("SOURCE_TYPE", result.getString("source_type"));
                    dataset.put("CUSTOMER_COUNT", result.getInt("customer_count"));
                    dataset.put("VEHICLE_COUNT", result.getInt("vehicle_count"));
                    dataset.put("DEPOT_COUNT", result.getInt("depot_count"));
                    dataset.put("DISTANCE_TYPE", result.getString("distance_type"));
                    dataset.put("TIME_DEPENDENT", result.getBoolean("time_dependent") ? 1 : 0);
                    dataset.put("RANDOM_SEED", result.getObject("random_seed"));
                    dataset.put("DESCRIPTION", result.getString("description"));
                    response.put("dataset", dataset);
                }
            }

            response.put("orders", queryRows(connection, """
                    SELECT order_id, order_code, customer_code, customer_name,
                           demand_weight, demand_volume, ready_time, due_time,
                           service_time, required_vehicle_type, priority, status
                    FROM v_order_problem_input
                    WHERE dataset_id = ?
                    ORDER BY order_id
                    """, id));
            response.put("vehicles", queryRows(connection, """
                    SELECT vehicle_id, vehicle_code, vehicle_type,
                           capacity_weight, capacity_volume,
                           available_from, available_to,
                           fixed_cost, cost_per_km, cost_per_minute, is_active
                    FROM vehicle
                    WHERE dataset_id = ?
                    ORDER BY vehicle_id
                    """, id));
            response.put("trafficProfileCount", scalarCount(
                    connection,
                    "SELECT COUNT(*) FROM travel_time_profile WHERE dataset_id = ?",
                    id));
            return ResponseEntity.ok(response);
        } catch (Exception exception) {
            exception.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Lỗi đọc dataset: " + exception.getMessage()));
        }
    }

    /**
     * Sinh dataset quanh tọa độ trung tâm. TDVRPTW được tạo thêm traffic profile
     * cho từng cung; VRPTW/TDVRPTW có cửa sổ thời gian hẹp hơn CVRP.
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generateDataset(
            @RequestBody Map<String, Object> payload) {
        final GeneratorRequest request;
        try {
            request = GeneratorRequest.from(payload);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        }

        Random random = new Random(request.seed());
        String code = "DS_" + System.currentTimeMillis();
        String name = request.name().isBlank()
                ? "Dataset " + request.datasetType() + " " + request.customerCount() + " điểm"
                : request.name();

        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                long datasetId = insertDataset(connection, code, name, request);
                long depotLocationId = insertLocation(
                        connection,
                        datasetId,
                        "D0",
                        "Kho trung tâm (" + name + ')',
                        "DEPOT",
                        request.centerLat(),
                        request.centerLng(),
                        0,
                        0);
                long depotId = insertDepot(connection, datasetId, depotLocationId);

                List<LocationPoint> points = new ArrayList<>();
                points.add(new LocationPoint(
                        depotLocationId,
                        request.centerLat(),
                        request.centerLng()));
                insertCustomersAndOrders(connection, datasetId, request, random, points);
                insertVehicles(connection, datasetId, depotId, request);
                insertDistanceMatrix(connection, datasetId, points);
                if (request.timeDependent()) {
                    insertTrafficProfiles(connection, datasetId);
                }

                connection.commit();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("success", true);
                result.put("datasetId", datasetId);
                result.put("code", code);
                result.put("name", name);
                result.put("datasetType", request.datasetType());
                result.put("timeDependent", request.timeDependent());
                result.put("customerCount", request.customerCount());
                result.put("vehicleCount", request.vehicleCount());
                result.put("vehicleCapacity", request.vehicleCapacity());
                result.put("vehicleFixedCost", request.vehicleFixedCost());
                result.put("costPerKm", request.costPerKm());
                result.put("costPerMinute", request.costPerMinute());
                return ResponseEntity.ok(result);
            } catch (Exception exception) {
                connection.rollback();
                exception.printStackTrace();
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", "Lỗi tạo dataset: " + exception.getMessage()));
            }
        } catch (Exception exception) {
            exception.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Lỗi kết nối database: " + exception.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteDataset(@PathVariable("id") long id) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                // experiment -> result -> route -> stop và parameter/log đã ON DELETE CASCADE.
                execute(connection, "DELETE FROM experiment WHERE dataset_id = ?", id);
                execute(connection, "DELETE FROM travel_time_profile WHERE dataset_id = ?", id);
                execute(connection, "DELETE FROM location_distance WHERE dataset_id = ?", id);
                execute(connection, "DELETE FROM delivery_order WHERE dataset_id = ?", id);
                execute(connection, "DELETE FROM customer WHERE dataset_id = ?", id);
                execute(connection, "DELETE FROM driver WHERE dataset_id = ?", id);
                execute(connection, "DELETE FROM vehicle WHERE dataset_id = ?", id);
                execute(connection, "DELETE FROM depot WHERE dataset_id = ?", id);
                execute(connection, "DELETE FROM location WHERE dataset_id = ?", id);
                int affected = execute(connection, "DELETE FROM dataset WHERE dataset_id = ?", id);
                if (affected == 0) {
                    connection.rollback();
                    return ResponseEntity.notFound().build();
                }
                connection.commit();
                return ResponseEntity.ok(Map.of("success", true, "datasetId", id));
            } catch (Exception exception) {
                connection.rollback();
                exception.printStackTrace();
                return ResponseEntity.internalServerError()
                        .body(Map.of("success", false, "error", exception.getMessage()));
            }
        } catch (Exception exception) {
            exception.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", exception.getMessage()));
        }
    }

    private long insertDataset(
            Connection connection,
            String code,
            String name,
            GeneratorRequest request) throws SQLException {
        String sql = """
                INSERT INTO dataset(
                    dataset_code, dataset_name, dataset_type, source_type,
                    customer_count, vehicle_count, depot_count, distance_type,
                    time_dependent, version_no, generator_version,
                    random_seed, checksum, description, created_at)
                VALUES (?, ?, ?, 'SYNTHETIC', ?, ?, 1, 'HAVERSINE', ?,
                        '2.0', 'generator-v2', ?, ?, ?, NOW())
                """;
        try (var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, code);
            statement.setString(2, name);
            statement.setString(3, request.datasetType());
            statement.setInt(4, request.customerCount());
            statement.setInt(5, request.vehicleCount());
            statement.setBoolean(6, request.timeDependent());
            statement.setLong(7, request.seed());
            statement.setString(8, "GEN-" + request.seed());
            statement.setString(9, "Sinh tự động " + request.datasetType()
                    + ": " + request.customerCount() + " khách, "
                    + request.vehicleCount() + " xe");
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    private long insertLocation(
            Connection connection,
            long datasetId,
            String code,
            String name,
            String type,
            double latitude,
            double longitude,
            double x,
            double y) throws SQLException {
        String sql = """
                INSERT INTO location(
                    dataset_id, location_code, location_name, location_type,
                    latitude, longitude, x_coordinate, y_coordinate, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())
                """;
        try (var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, datasetId);
            statement.setString(2, code);
            statement.setString(3, name);
            statement.setString(4, type);
            statement.setDouble(5, latitude);
            statement.setDouble(6, longitude);
            statement.setDouble(7, x);
            statement.setDouble(8, y);
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    private long insertDepot(Connection connection, long datasetId, long locationId)
            throws SQLException {
        String sql = """
                INSERT INTO depot(
                    dataset_id, location_id, depot_code, depot_name,
                    open_time, close_time, is_active)
                VALUES (?, ?, 'D0', 'Kho trung tâm', 28800, 64800, 1)
                """;
        try (var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, datasetId);
            statement.setLong(2, locationId);
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    private void insertCustomersAndOrders(
            Connection connection,
            long datasetId,
            GeneratorRequest request,
            Random random,
            List<LocationPoint> points) throws SQLException {
        String customerSql = """
                INSERT INTO customer(
                    dataset_id, location_id, customer_code, customer_name,
                    service_time, time_window_start, time_window_end,
                    priority, is_active)
                VALUES (?, ?, ?, ?, 600, ?, ?, 1, 1)
                """;
        String orderSql = """
                INSERT INTO delivery_order(
                    dataset_id, customer_id, order_code,
                    demand_weight, demand_volume,
                    ready_time, due_time, service_time,
                    status, priority, created_at)
                VALUES (?, ?, ?, ?, 0, ?, ?, 600, 'PENDING', 1, NOW())
                """;

        for (int index = 1; index <= request.customerCount(); index++) {
            double angle = random.nextDouble() * 2 * Math.PI;
            double radius = request.radiusKm() * Math.sqrt(random.nextDouble());
            double x = radius * Math.cos(angle);
            double y = radius * Math.sin(angle);
            double latitude = request.centerLat() + y / 111.0;
            double longitude = request.centerLng()
                    + x / (111.0 * Math.cos(Math.toRadians(request.centerLat())));
            long locationId = insertLocation(
                    connection,
                    datasetId,
                    "C" + index,
                    "Khách hàng " + index,
                    "CUSTOMER",
                    latitude,
                    longitude,
                    x,
                    y);
            points.add(new LocationPoint(locationId, latitude, longitude));

            int readyTime = 28800;
            int dueTime = 64800;
            if (!"CVRP".equals(request.datasetType())) {
                readyTime = 28800 + random.nextInt(7 * 3600 + 1);
                dueTime = Math.min(64800, readyTime + (2 + random.nextInt(3)) * 3600);
            }

            long customerId;
            try (var statement = connection.prepareStatement(
                    customerSql,
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, datasetId);
                statement.setLong(2, locationId);
                statement.setString(3, "C" + index);
                statement.setString(4, "Khách hàng " + index);
                statement.setInt(5, readyTime);
                statement.setInt(6, dueTime);
                statement.executeUpdate();
                customerId = generatedKey(statement);
            }

            double demand = Math.round((request.minDemand()
                    + random.nextDouble() * (request.maxDemand() - request.minDemand())) * 10) / 10.0;
            try (var statement = connection.prepareStatement(orderSql)) {
                statement.setLong(1, datasetId);
                statement.setLong(2, customerId);
                statement.setString(3, "O" + index);
                statement.setDouble(4, demand);
                statement.setInt(5, readyTime);
                statement.setInt(6, dueTime);
                statement.executeUpdate();
            }
        }
    }

    private void insertVehicles(
            Connection connection,
            long datasetId,
            long depotId,
            GeneratorRequest request) throws SQLException {
        String sql = """
                INSERT INTO vehicle(
                    dataset_id, vehicle_code, vehicle_type,
                    capacity_weight, capacity_volume,
                    start_depot_id, end_depot_id,
                    available_from, available_to,
                    fixed_cost, cost_per_km, cost_per_minute, is_active)
                VALUES (?, ?, 'STANDARD', ?, 0, ?, ?, 28800, 64800, ?, ?, ?, 1)
                """;
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 1; index <= request.vehicleCount(); index++) {
                statement.setLong(1, datasetId);
                statement.setString(2, "V" + index);
                statement.setDouble(3, request.vehicleCapacity());
                statement.setLong(4, depotId);
                statement.setLong(5, depotId);
                statement.setDouble(6, request.vehicleFixedCost());
                statement.setDouble(7, request.costPerKm());
                statement.setDouble(8, request.costPerMinute());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertDistanceMatrix(
            Connection connection,
            long datasetId,
            List<LocationPoint> points) throws SQLException {
        String sql = """
                INSERT INTO location_distance(
                    dataset_id, from_location_id, to_location_id,
                    distance, base_travel_time)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (var statement = connection.prepareStatement(sql)) {
            for (LocationPoint from : points) {
                for (LocationPoint to : points) {
                    if (from.id() == to.id()) {
                        continue;
                    }
                    double distance = Math.max(
                            0.05,
                            haversine(from.latitude(), from.longitude(), to.latitude(), to.longitude()));
                    int travelTime = Math.max(1, (int) Math.round(distance / 30.0 * 3600));
                    statement.setLong(1, datasetId);
                    statement.setLong(2, from.id());
                    statement.setLong(3, to.id());
                    statement.setDouble(4, Math.round(distance * 100) / 100.0);
                    statement.setInt(5, travelTime);
                    statement.addBatch();
                }
            }
            statement.executeBatch();
        }
    }

    /** Tạo profile phủ kín 24 giờ, không chồng lấn. */
    private void insertTrafficProfiles(Connection connection, long datasetId)
            throws SQLException {
        String select = "SELECT distance_id, base_travel_time "
                + "FROM location_distance WHERE dataset_id = ?";
        String insert = """
                INSERT INTO travel_time_profile(
                    dataset_id, distance_id, start_time, end_time,
                    travel_time, speed_factor, traffic_level)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        Object[][] bands = {
                {0, 25200, 1.00, "FREE"},
                {25200, 32400, 0.60, "CONGESTED"},
                {32400, 57600, 0.85, "NORMAL"},
                {57600, 68400, 0.55, "SEVERE"},
                {68400, 86400, 0.90, "NORMAL"}
        };
        try (var query = connection.prepareStatement(select);
                var write = connection.prepareStatement(insert)) {
            query.setLong(1, datasetId);
            try (var result = query.executeQuery()) {
                while (result.next()) {
                    long distanceId = result.getLong("distance_id");
                    int base = result.getInt("base_travel_time");
                    for (Object[] band : bands) {
                        double factor = (double) band[2];
                        write.setLong(1, datasetId);
                        write.setLong(2, distanceId);
                        write.setInt(3, (int) band[0]);
                        write.setInt(4, (int) band[1]);
                        write.setInt(5, Math.max(1, (int) Math.round(base / factor)));
                        write.setDouble(6, factor);
                        write.setString(7, (String) band[3]);
                        write.addBatch();
                    }
                }
            }
            write.executeBatch();
        }
    }

    private List<Map<String, Object>> queryRows(
            Connection connection,
            String sql,
            long id) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (var result = statement.executeQuery()) {
                ResultSetMetaData metadata = result.getMetaData();
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int column = 1; column <= metadata.getColumnCount(); column++) {
                        row.put(
                                metadata.getColumnLabel(column).toUpperCase(Locale.ROOT),
                                result.getObject(column));
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private long scalarCount(Connection connection, String sql, long id) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private int execute(Connection connection, String sql, long id) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            return statement.executeUpdate();
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

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        double earthRadiusKm = 6371.0;
        double deltaLat = Math.toRadians(lat2 - lat1);
        double deltaLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        return earthRadiusKm * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private Connection connect() throws Exception {
        return DatabaseConfig.from(DatabaseConfig.loadProperties()).connect();
    }

    private record LocationPoint(long id, double latitude, double longitude) {}

    private record GeneratorRequest(
            String name,
            String datasetType,
            int customerCount,
            int vehicleCount,
            double vehicleCapacity,
            double vehicleFixedCost,
            double costPerKm,
            double costPerMinute,
            double minDemand,
            double maxDemand,
            double centerLat,
            double centerLng,
            double radiusKm,
            long seed) {

        private boolean timeDependent() {
            return "TDVRPTW".equals(datasetType);
        }

        private static GeneratorRequest from(Map<String, Object> payload) {
            try {
                String name = text(payload, "name", "");
                String type = text(payload, "datasetType", "CVRP").toUpperCase(Locale.ROOT);
                int customers = integer(payload, "customerCount", 8);
                int vehicles = integer(payload, "vehicleCount", 2);
                double capacity = decimal(payload, "vehicleCapacity", 40);
                double fixedCost = decimal(payload, "vehicleFixedCost", 500_000);
                double costPerKm = decimal(payload, "costPerKm", 15_000);
                double costPerMinute = decimal(payload, "costPerMinute", 3_000);
                double minDemand = decimal(payload, "minDemand", 5);
                double maxDemand = decimal(payload, "maxDemand", 15);
                double centerLat = decimal(payload, "centerLat", 10.7769);
                double centerLng = decimal(payload, "centerLng", 106.7009);
                double radius = decimal(payload, "radiusKm", 6);
                long seed = longNumber(payload, "seed", System.currentTimeMillis());

                if (!Set.of("CVRP", "VRPTW", "TDVRPTW").contains(type)) {
                    throw new IllegalArgumentException(
                            "datasetType phải là CVRP, VRPTW hoặc TDVRPTW");
                }
                if (customers <= 0 || customers > 500
                        || vehicles <= 0 || vehicles > 100
                        || capacity <= 0
                        || fixedCost < 0 || costPerKm < 0 || costPerMinute < 0
                        || minDemand <= 0 || maxDemand < minDemand
                        || radius <= 0 || radius > 500
                        || centerLat < -90 || centerLat > 90
                        || centerLng < -180 || centerLng > 180) {
                    throw new IllegalArgumentException(
                            "Kiểm tra số khách/xe, capacity, chi phí, demand, radius và tọa độ");
                }
                return new GeneratorRequest(
                        name,
                        type,
                        customers,
                        vehicles,
                        capacity,
                        fixedCost,
                        costPerKm,
                        costPerMinute,
                        minDemand,
                        maxDemand,
                        centerLat,
                        centerLng,
                        radius,
                        seed);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Tham số số không đúng định dạng", exception);
            }
        }

        private static String text(Map<String, Object> values, String key, String fallback) {
            Object value = values.get(key);
            return value == null ? fallback : value.toString();
        }

        private static int integer(Map<String, Object> values, String key, int fallback) {
            Object value = values.get(key);
            return value == null ? fallback : Integer.parseInt(value.toString());
        }

        private static long longNumber(Map<String, Object> values, String key, long fallback) {
            Object value = values.get(key);
            return value == null ? fallback : Long.parseLong(value.toString());
        }

        private static double decimal(Map<String, Object> values, String key, double fallback) {
            Object value = values.get(key);
            return value == null ? fallback : Double.parseDouble(value.toString());
        }
    }
}
