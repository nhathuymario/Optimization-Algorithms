package vn.vrp.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.*;
import java.sql.*;
import vn.vrp.db.DatabaseConfig;
import vn.vrp.algorithm.greedy.GreedyCvrpSolver;
import vn.vrp.db.*;
import vn.vrp.model.*;
import vn.vrp.validator.*;

@RestController
@RequestMapping("/api/experiments")
@CrossOrigin(origins = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS}) // Allow React to call this API
public class VrpController {

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAllExperiments() {
        List<Map<String, Object>> result = new ArrayList<>();
        String sql = "SELECT e.EXPERIMENT_ID, e.EXPERIMENT_CODE, e.START_TIME, e.STATUS, " +
                     "r.TOTAL_DISTANCE, r.VEHICLE_USED, r.IS_FEASIBLE " +
                     "FROM EXPERIMENT e " +
                     "LEFT JOIN EXPERIMENT_RESULT r ON e.EXPERIMENT_ID = r.EXPERIMENT_ID " +
                     "ORDER BY e.EXPERIMENT_ID DESC";

        try (Connection conn = DatabaseConfig.from(DatabaseConfig.loadProperties()).connect();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            
            while (rs.next()) {
                Map<String, Object> map = new HashMap<>();
                map.put("EXPERIMENT_ID", rs.getLong("EXPERIMENT_ID"));
                map.put("EXPERIMENT_CODE", rs.getString("EXPERIMENT_CODE"));
                map.put("START_TIME", rs.getString("START_TIME"));
                map.put("STATUS", rs.getString("STATUS"));
                map.put("TOTAL_DISTANCE", rs.getString("TOTAL_DISTANCE"));
                map.put("VEHICLE_USED", rs.getInt("VEHICLE_USED"));
                map.put("IS_FEASIBLE", rs.getBoolean("IS_FEASIBLE") ? 1 : 0);
                result.add(map);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getExperimentDetail(@PathVariable("id") long id) {
        Map<String, Object> response = new HashMap<>();
        
        try (Connection conn = DatabaseConfig.from(DatabaseConfig.loadProperties()).connect()) {
            // 1. Get experiment and result info
            Map<String, Object> experiment = new HashMap<>();
            long datasetId = 0;
            long resultId = 0;
            
            try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT * FROM EXPERIMENT e JOIN EXPERIMENT_RESULT r ON e.EXPERIMENT_ID = r.EXPERIMENT_ID WHERE e.EXPERIMENT_ID = ?")) {
                stmt.setLong(1, id);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    datasetId = rs.getLong("DATASET_ID");
                    resultId = rs.getLong("RESULT_ID");
                    experiment.put("EXPERIMENT_ID", rs.getLong("EXPERIMENT_ID"));
                    experiment.put("EXPERIMENT_CODE", rs.getString("EXPERIMENT_CODE"));
                    experiment.put("TOTAL_DISTANCE", rs.getString("TOTAL_DISTANCE"));
                    experiment.put("OBJECTIVE_VALUE", rs.getString("OBJECTIVE_VALUE"));
                    experiment.put("VEHICLE_USED", rs.getInt("VEHICLE_USED"));
                    experiment.put("IS_FEASIBLE", rs.getBoolean("IS_FEASIBLE") ? 1 : 0);
                } else {
                    return ResponseEntity.notFound().build();
                }
            }
            
            // 2. Get Depots
            List<Map<String, Object>> depots = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement("SELECT d.DEPOT_ID, d.DEPOT_CODE, COALESCE(l.LATITUDE, l.Y_COORDINATE, 0) AS LATITUDE, COALESCE(l.LONGITUDE, l.X_COORDINATE, 0) AS LONGITUDE FROM DEPOT d JOIN LOCATION l ON d.LOCATION_ID = l.LOCATION_ID WHERE d.DATASET_ID = ?")) {
                stmt.setLong(1, datasetId);
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    Map<String, Object> d = new HashMap<>();
                    d.put("DEPOT_ID", rs.getLong("DEPOT_ID"));
                    d.put("DEPOT_CODE", rs.getString("DEPOT_CODE"));
                    d.put("LATITUDE", rs.getDouble("LATITUDE"));
                    d.put("LONGITUDE", rs.getDouble("LONGITUDE"));
                    depots.add(d);
                }
            }
            
            // 3. Get Customers
            List<Map<String, Object>> customers = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement("SELECT c.CUSTOMER_ID, c.CUSTOMER_CODE, COALESCE(l.LATITUDE, l.Y_COORDINATE, 0) AS LATITUDE, COALESCE(l.LONGITUDE, l.X_COORDINATE, 0) AS LONGITUDE FROM CUSTOMER c JOIN LOCATION l ON c.LOCATION_ID = l.LOCATION_ID WHERE c.DATASET_ID = ?")) {
                stmt.setLong(1, datasetId);
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    Map<String, Object> c = new HashMap<>();
                    c.put("CUSTOMER_ID", rs.getLong("CUSTOMER_ID"));
                    c.put("CUSTOMER_CODE", rs.getString("CUSTOMER_CODE"));
                    c.put("LATITUDE", rs.getDouble("LATITUDE"));
                    c.put("LONGITUDE", rs.getDouble("LONGITUDE"));
                    customers.add(c);
                }
            }

            // 4. Get Routes and Stops
            List<Map<String, Object>> routes = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT r.*, v.VEHICLE_CODE FROM ROUTE_RESULT r LEFT JOIN VEHICLE v ON r.VEHICLE_ID = v.VEHICLE_ID WHERE r.RESULT_ID = ?")) {
                stmt.setLong(1, resultId);
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    Map<String, Object> r = new HashMap<>();
                    long routeId = rs.getLong("ROUTE_ID");
                    r.put("ROUTE_ID", routeId);
                    r.put("VEHICLE_CODE", rs.getString("VEHICLE_CODE"));
                    r.put("TOTAL_LOAD", rs.getString("TOTAL_LOAD_WEIGHT"));
                    r.put("TOTAL_DISTANCE", rs.getString("TOTAL_DISTANCE"));
                    
                    List<Map<String, Object>> stops = new ArrayList<>();
                    try (PreparedStatement stopStmt = conn.prepareStatement(
                        "SELECT s.*, COALESCE(l.LATITUDE, l.Y_COORDINATE, 0) AS LATITUDE, COALESCE(l.LONGITUDE, l.X_COORDINATE, 0) AS LONGITUDE, c.CUSTOMER_CODE, d.DEPOT_CODE " +
                        "FROM ROUTE_STOP_RESULT s " +
                        "JOIN LOCATION l ON s.LOCATION_ID = l.LOCATION_ID " +
                        "LEFT JOIN CUSTOMER c ON s.LOCATION_ID = c.LOCATION_ID " +
                        "LEFT JOIN DEPOT d ON s.LOCATION_ID = d.LOCATION_ID " +
                        "WHERE s.ROUTE_ID = ? ORDER BY s.SEQUENCE_NO")) {
                        stopStmt.setLong(1, routeId);
                        ResultSet sRs = stopStmt.executeQuery();
                        while (sRs.next()) {
                            Map<String, Object> s = new HashMap<>();
                            s.put("SEQUENCE_NO", sRs.getInt("SEQUENCE_NO"));
                            s.put("LATITUDE", sRs.getDouble("LATITUDE"));
                            s.put("LONGITUDE", sRs.getDouble("LONGITUDE"));
                            String cCode = sRs.getString("CUSTOMER_CODE");
                            String dCode = sRs.getString("DEPOT_CODE");
                            s.put("CODE", cCode != null ? cCode : (dCode != null ? dCode : "LOC_" + sRs.getLong("LOCATION_ID")));
                            stops.add(s);
                        }
                    }
                    r.put("stops", stops);
                    routes.add(r);
                }
            }

            response.put("experiment", experiment);
            response.put("depots", depots);
            response.put("customers", customers);
            response.put("routes", routes);
            
        } catch (Exception e) {
            e.printStackTrace();
            Map<String, Object> err = new HashMap<>();
            err.put("error", e.getMessage());
            err.put("stacktrace", java.util.Arrays.toString(e.getStackTrace()));
            try (Connection c = DatabaseConfig.from(DatabaseConfig.loadProperties()).connect()) {
                ResultSet rs1 = c.createStatement().executeQuery("SELECT * FROM DEPOT LIMIT 1");
                ResultSetMetaData md1 = rs1.getMetaData();
                List<String> depotCols = new ArrayList<>();
                for(int i=1; i<=md1.getColumnCount(); i++) depotCols.add(md1.getColumnName(i));
                err.put("DEPOT_COLUMNS", depotCols);
                
                ResultSet rs2 = c.createStatement().executeQuery("SELECT * FROM CUSTOMER LIMIT 1");
                ResultSetMetaData md2 = rs2.getMetaData();
                List<String> custCols = new ArrayList<>();
                for(int i=1; i<=md2.getColumnCount(); i++) custCols.add(md2.getColumnName(i));
                err.put("CUSTOMER_COLUMNS", custCols);
            } catch(Exception ex) {}
            return ResponseEntity.status(500).body((Map) err);
        }
        
        return ResponseEntity.ok(response);
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runDemoExperiment(@RequestBody Map<String, Object> payload) {
        long datasetId = 1;
        long seed = System.currentTimeMillis();
        double capacityOverride = -1;

        if (payload != null) {
            if (payload.containsKey("datasetId") && payload.get("datasetId") != null) {
                datasetId = Long.parseLong(payload.get("datasetId").toString());
            }
            if (payload.containsKey("seed") && payload.get("seed") != null) {
                seed = Long.parseLong(payload.get("seed").toString());
            }
            if (payload.containsKey("capacityOverride") && payload.get("capacityOverride") != null) {
                try {
                    double c = Double.parseDouble(payload.get("capacityOverride").toString());
                    if (c > 0) capacityOverride = c;
                } catch (Exception ignored) {}
            }
        }
        
        long experimentId = -1;
        try (Connection connection = DatabaseConfig.from(DatabaseConfig.loadProperties()).connect()) {
            ProblemInstance instance = new ProblemInstanceDao().load(connection, datasetId);

            // If capacity override is specified, replace vehicles with overridden capacity
            if (capacityOverride > 0) {
                List<Vehicle> modVehicles = new ArrayList<>();
                for (Vehicle v : instance.vehicles()) {
                    modVehicles.add(new Vehicle(v.id(), v.code(), v.type(), capacityOverride, v.capacityVolume(),
                        v.startDepotId(), v.endDepotId(), v.availableFrom(), v.availableTo(), v.fixedCost(), v.costPerKm(), v.costPerMinute()));
                }
                instance = new ProblemInstance(instance.datasetId(), instance.code(), instance.type(), instance.depot(), instance.customers(), modVehicles, instance.arcs());
            }

            Solution solution = new GreedyCvrpSolver().solve(instance);
            ValidationResult validation = new SolutionValidator().validate(instance, solution);
            
            experimentId = new ExperimentDao().save(connection, instance, solution, validation, seed);
        } catch (IllegalStateException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(err);
        } catch (Exception e) {
            e.printStackTrace();
            Map<String, Object> err = new HashMap<>();
            err.put("error", "Lỗi thực thi: " + e.getMessage());
            return ResponseEntity.status(500).body(err);
        }
        
        return getExperimentDetail(experimentId);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteExperiment(@PathVariable("id") long id) {
        Map<String, Object> response = new HashMap<>();
        try (Connection conn = DatabaseConfig.from(DatabaseConfig.loadProperties()).connect()) {
            conn.setAutoCommit(false);
            try {
                // Delete EXPERIMENT_PARAMETER_VALUE
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM EXPERIMENT_PARAMETER_VALUE WHERE EXPERIMENT_ID = ?")) {
                    stmt.setLong(1, id);
                    stmt.executeUpdate();
                }

                // Delete ROUTE_STOP_RESULT
                try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM ROUTE_STOP_RESULT WHERE ROUTE_ID IN (SELECT ROUTE_ID FROM ROUTE_RESULT WHERE RESULT_ID IN (SELECT RESULT_ID FROM EXPERIMENT_RESULT WHERE EXPERIMENT_ID = ?))")) {
                    stmt.setLong(1, id);
                    stmt.executeUpdate();
                }

                // Delete ROUTE_RESULT
                try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM ROUTE_RESULT WHERE RESULT_ID IN (SELECT RESULT_ID FROM EXPERIMENT_RESULT WHERE EXPERIMENT_ID = ?)")) {
                    stmt.setLong(1, id);
                    stmt.executeUpdate();
                }

                // Delete EXPERIMENT_RESULT
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM EXPERIMENT_RESULT WHERE EXPERIMENT_ID = ?")) {
                    stmt.setLong(1, id);
                    stmt.executeUpdate();
                }

                // Delete EXPERIMENT
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM EXPERIMENT WHERE EXPERIMENT_ID = ?")) {
                    stmt.setLong(1, id);
                    stmt.executeUpdate();
                }
                
                conn.commit();
                response.put("success", true);
                return ResponseEntity.ok(response);
            } catch (Exception e) {
                conn.rollback();
                e.printStackTrace();
                response.put("success", false);
                response.put("error", e.getMessage());
                return ResponseEntity.status(500).body(response);
            }
        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }
}
