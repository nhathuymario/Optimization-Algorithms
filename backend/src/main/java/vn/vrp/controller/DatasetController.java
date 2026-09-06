package vn.vrp.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.*;
import java.sql.*;
import vn.vrp.db.DatabaseConfig;

@RestController
@RequestMapping("/api/datasets")
@CrossOrigin(origins = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class DatasetController {

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAllDatasets() {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT DATASET_ID, DATASET_CODE, DATASET_NAME, DATASET_TYPE, CUSTOMER_COUNT, VEHICLE_COUNT, CREATED_AT " +
                     "FROM DATASET ORDER BY DATASET_ID DESC";

        try (Connection conn = DatabaseConfig.from(DatabaseConfig.loadProperties()).connect();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                Map<String, Object> item = new HashMap<>();
                item.put("DATASET_ID", rs.getLong("DATASET_ID"));
                item.put("DATASET_CODE", rs.getString("DATASET_CODE"));
                item.put("DATASET_NAME", rs.getString("DATASET_NAME"));
                item.put("DATASET_TYPE", rs.getString("DATASET_TYPE"));
                item.put("CUSTOMER_COUNT", rs.getInt("CUSTOMER_COUNT"));
                item.put("VEHICLE_COUNT", rs.getInt("VEHICLE_COUNT"));
                item.put("CREATED_AT", rs.getString("CREATED_AT"));
                list.add(item);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
        return ResponseEntity.ok(list);
    }

    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generateDataset(@RequestBody Map<String, Object> payload) {
        String name = payload.getOrDefault("name", "").toString();
        int customerCount = payload.containsKey("customerCount") ? Integer.parseInt(payload.get("customerCount").toString()) : 8;
        int vehicleCount = payload.containsKey("vehicleCount") ? Integer.parseInt(payload.get("vehicleCount").toString()) : 2;
        double vehicleCapacity = payload.containsKey("vehicleCapacity") ? Double.parseDouble(payload.get("vehicleCapacity").toString()) : 40.0;
        double minDemand = payload.containsKey("minDemand") ? Double.parseDouble(payload.get("minDemand").toString()) : 5.0;
        double maxDemand = payload.containsKey("maxDemand") ? Double.parseDouble(payload.get("maxDemand").toString()) : 15.0;
        double centerLat = payload.containsKey("centerLat") ? Double.parseDouble(payload.get("centerLat").toString()) : 10.7769; // Default: TP.HCM
        double centerLng = payload.containsKey("centerLng") ? Double.parseDouble(payload.get("centerLng").toString()) : 106.7009;
        double radiusKm = payload.containsKey("radiusKm") ? Double.parseDouble(payload.get("radiusKm").toString()) : 6.0;
        long seed = payload.containsKey("seed") ? Long.parseLong(payload.get("seed").toString()) : System.currentTimeMillis();

        if (customerCount <= 0 || vehicleCount <= 0 || vehicleCapacity <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Tham số không hợp lệ: số khách, số xe và tải trọng phải > 0"));
        }

        Random rand = new Random(seed);
        String code = "DS_" + System.currentTimeMillis();
        if (name == null || name.isBlank()) {
            name = "Dataset " + customerCount + " điểm (" + vehicleCount + " xe)";
        }

        try (Connection conn = DatabaseConfig.from(DatabaseConfig.loadProperties()).connect()) {
            conn.setAutoCommit(false);
            try {
                // 1. Insert DATASET
                long datasetId;
                String dsSql = "INSERT INTO dataset (dataset_code, dataset_name, dataset_type, source_type, " +
                               "customer_count, vehicle_count, depot_count, distance_type, time_dependent, " +
                               "version_no, generator_version, random_seed, checksum, description, created_at) " +
                               "VALUES (?, ?, 'CVRP', 'SYNTHETIC', ?, ?, 1, 'HAVERSINE', 0, '1.0', 'generator-v1', ?, ?, ?, NOW())";
                try (PreparedStatement ps = conn.prepareStatement(dsSql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, code);
                    ps.setString(2, name);
                    ps.setInt(3, customerCount);
                    ps.setInt(4, vehicleCount);
                    ps.setLong(5, seed);
                    ps.setString(6, "CHECKSUM_" + seed);
                    ps.setString(7, "Sinh tự động từ Web UI: " + customerCount + " khách, " + vehicleCount + " xe, capacity=" + vehicleCapacity);
                    ps.executeUpdate();
                    ResultSet keys = ps.getGeneratedKeys();
                    if (!keys.next()) throw new SQLException("Không lấy được generated ID cho dataset");
                    datasetId = keys.getLong(1);
                }

                // 2. Insert Depot Location
                long depotLocationId;
                String locSql = "INSERT INTO location (dataset_id, location_code, location_name, location_type, latitude, longitude, x_coordinate, y_coordinate, created_at) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())";
                try (PreparedStatement ps = conn.prepareStatement(locSql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, datasetId);
                    ps.setString(2, "D0");
                    ps.setString(3, "Kho Trung Tâm (" + name + ")");
                    ps.setString(4, "DEPOT");
                    ps.setDouble(5, centerLat);
                    ps.setDouble(6, centerLng);
                    ps.setDouble(7, 0.0);
                    ps.setDouble(8, 0.0);
                    ps.executeUpdate();
                    ResultSet keys = ps.getGeneratedKeys();
                    if (!keys.next()) throw new SQLException("Không lấy được generated ID cho depot location");
                    depotLocationId = keys.getLong(1);
                }

                // 3. Insert DEPOT
                long depotId;
                String depotSql = "INSERT INTO depot (dataset_id, location_id, depot_code, depot_name, open_time, close_time, is_active) " +
                                  "VALUES (?, ?, 'D0', 'Kho Trung Tâm', 28800, 64800, 1)";
                try (PreparedStatement ps = conn.prepareStatement(depotSql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, datasetId);
                    ps.setLong(2, depotLocationId);
                    ps.executeUpdate();
                    ResultSet keys = ps.getGeneratedKeys();
                    if (!keys.next()) throw new SQLException("Không lấy được generated ID cho depot");
                    depotId = keys.getLong(1);
                }

                // 4. Insert Customers & Locations & Delivery Orders
                List<LocationPoint> allPoints = new ArrayList<>();
                allPoints.add(new LocationPoint(depotLocationId, "D0", centerLat, centerLng));

                for (int i = 1; i <= customerCount; i++) {
                    double angle = rand.nextDouble() * 2 * Math.PI;
                    double r = radiusKm * Math.sqrt(rand.nextDouble()); // uniform disk
                    double dLat = (r * Math.cos(angle)) / 111.0;
                    double dLng = (r * Math.sin(angle)) / (111.0 * Math.cos(Math.toRadians(centerLat)));
                    double cLat = centerLat + dLat;
                    double cLng = centerLng + dLng;

                    long custLocId;
                    try (PreparedStatement ps = conn.prepareStatement(locSql, Statement.RETURN_GENERATED_KEYS)) {
                        ps.setLong(1, datasetId);
                        ps.setString(2, "C" + i);
                        ps.setString(3, "Khách hàng #" + i);
                        ps.setString(4, "CUSTOMER");
                        ps.setDouble(5, cLat);
                        ps.setDouble(6, cLng);
                        ps.setDouble(7, r * Math.cos(angle));
                        ps.setDouble(8, r * Math.sin(angle));
                        ps.executeUpdate();
                        ResultSet keys = ps.getGeneratedKeys();
                        if (!keys.next()) throw new SQLException("Không lấy được generated ID cho customer location");
                        custLocId = keys.getLong(1);
                    }

                    allPoints.add(new LocationPoint(custLocId, "C" + i, cLat, cLng));

                    long customerId;
                    String custSql = "INSERT INTO customer (dataset_id, location_id, customer_code, customer_name, service_time, time_window_start, time_window_end, priority, is_active) " +
                                     "VALUES (?, ?, ?, ?, 600, 28800, 64800, 1, 1)";
                    try (PreparedStatement ps = conn.prepareStatement(custSql, Statement.RETURN_GENERATED_KEYS)) {
                        ps.setLong(1, datasetId);
                        ps.setLong(2, custLocId);
                        ps.setString(3, "C" + i);
                        ps.setString(4, "Khách hàng " + i);
                        ps.executeUpdate();
                        ResultSet keys = ps.getGeneratedKeys();
                        if (!keys.next()) throw new SQLException("Không lấy được customerId");
                        customerId = keys.getLong(1);
                    }

                    double demand = Math.round((minDemand + rand.nextDouble() * (maxDemand - minDemand)) * 10.0) / 10.0;
                    if (demand < 1.0) demand = 1.0;

                    String orderSql = "INSERT INTO delivery_order (dataset_id, customer_id, order_code, demand_weight, demand_volume, ready_time, due_time, service_time, status, priority, created_at) " +
                                      "VALUES (?, ?, ?, ?, 0.0, 28800, 64800, 600, 'PENDING', 1, NOW())";
                    try (PreparedStatement ps = conn.prepareStatement(orderSql)) {
                        ps.setLong(1, datasetId);
                        ps.setLong(2, customerId);
                        ps.setString(3, "O" + i);
                        ps.setDouble(4, demand);
                        ps.executeUpdate();
                    }
                }

                // 5. Insert Vehicles
                String vehSql = "INSERT INTO vehicle (dataset_id, vehicle_code, vehicle_type, capacity_weight, capacity_volume, start_depot_id, end_depot_id, available_from, available_to, fixed_cost, cost_per_km, cost_per_minute, is_active) " +
                                "VALUES (?, ?, 'STANDARD', ?, 0.0, ?, ?, 28800, 64800, 100.0, 1.0, 0.0, 1)";
                for (int v = 1; v <= vehicleCount; v++) {
                    try (PreparedStatement ps = conn.prepareStatement(vehSql)) {
                        ps.setLong(1, datasetId);
                        ps.setString(2, "V" + v);
                        ps.setDouble(3, vehicleCapacity);
                        ps.setLong(4, depotId);
                        ps.setLong(5, depotId);
                        ps.executeUpdate();
                    }
                }

                // 6. Insert Location Distance Matrix
                String distSql = "INSERT INTO location_distance (dataset_id, from_location_id, to_location_id, distance, base_travel_time) " +
                                 "VALUES (?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(distSql)) {
                    for (LocationPoint p1 : allPoints) {
                        for (LocationPoint p2 : allPoints) {
                            if (p1.id == p2.id) continue; // Database requires from_location_id != to_location_id

                            double distKm = calcHaversine(p1.lat, p1.lng, p2.lat, p2.lng);
                            if (distKm < 0.05) distKm = 0.05;
                            int travelSec = (int) Math.round((distKm / 30.0) * 3600.0); // 30 km/h average city speed

                            ps.setLong(1, datasetId);
                            ps.setLong(2, p1.id);
                            ps.setLong(3, p2.id);
                            ps.setDouble(4, Math.round(distKm * 100.0) / 100.0);
                            ps.setInt(5, travelSec);
                            ps.addBatch();
                        }
                    }
                    ps.executeBatch();
                }

                conn.commit();

                Map<String, Object> resp = new HashMap<>();
                resp.put("success", true);
                resp.put("datasetId", datasetId);
                resp.put("code", code);
                resp.put("name", name);
                resp.put("customerCount", customerCount);
                resp.put("vehicleCount", vehicleCount);
                resp.put("vehicleCapacity", vehicleCapacity);
                return ResponseEntity.ok(resp);

            } catch (Exception ex) {
                conn.rollback();
                ex.printStackTrace();
                return ResponseEntity.internalServerError().body(Map.of("error", "Lỗi tạo dataset: " + ex.getMessage()));
            }
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteDataset(@PathVariable("id") long id) {
        try (Connection conn = DatabaseConfig.from(DatabaseConfig.loadProperties()).connect()) {
            conn.setAutoCommit(false);
            try {
                // Delete experiments associated with this dataset
                execute(conn, "DELETE FROM experiment_parameter_value WHERE experiment_id IN (SELECT experiment_id FROM experiment WHERE dataset_id = ?)", id);
                execute(conn, "DELETE FROM route_stop_result WHERE route_id IN (SELECT route_id FROM route_result WHERE result_id IN (SELECT result_id FROM experiment_result WHERE experiment_id IN (SELECT experiment_id FROM experiment WHERE dataset_id = ?)))", id);
                execute(conn, "DELETE FROM route_result WHERE result_id IN (SELECT result_id FROM experiment_result WHERE experiment_id IN (SELECT experiment_id FROM experiment WHERE dataset_id = ?))", id);
                execute(conn, "DELETE FROM experiment_result WHERE experiment_id IN (SELECT experiment_id FROM experiment WHERE dataset_id = ?)", id);
                execute(conn, "DELETE FROM experiment WHERE dataset_id = ?", id);

                // Delete dataset items
                execute(conn, "DELETE FROM location_distance WHERE dataset_id = ?", id);
                execute(conn, "DELETE FROM delivery_order WHERE dataset_id = ?", id);
                execute(conn, "DELETE FROM customer WHERE dataset_id = ?", id);
                execute(conn, "DELETE FROM vehicle WHERE dataset_id = ?", id);
                execute(conn, "DELETE FROM depot WHERE dataset_id = ?", id);
                execute(conn, "DELETE FROM location WHERE dataset_id = ?", id);
                execute(conn, "DELETE FROM dataset WHERE dataset_id = ?", id);

                conn.commit();
                return ResponseEntity.ok(Map.of("success", true, "datasetId", id));
            } catch (Exception e) {
                conn.rollback();
                e.printStackTrace();
                return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
            }
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private void execute(Connection conn, String sql, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private double calcHaversine(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371.0; // km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    private record LocationPoint(long id, String code, double lat, double lng) {}
}
