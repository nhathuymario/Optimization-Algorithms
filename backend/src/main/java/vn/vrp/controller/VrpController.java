package vn.vrp.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.*;
import java.sql.*;
import vn.vrp.db.DatabaseConfig;

@RestController
@RequestMapping("/api/experiments")
@CrossOrigin(origins = "*") // Allow React to call this API
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
            try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM DEPOT WHERE DATASET_ID = ?")) {
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
            try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM CUSTOMER WHERE DATASET_ID = ?")) {
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
                    r.put("TOTAL_LOAD", rs.getString("TOTAL_LOAD"));
                    r.put("TOTAL_DISTANCE", rs.getString("TOTAL_DISTANCE"));
                    
                    List<Map<String, Object>> stops = new ArrayList<>();
                    try (PreparedStatement stopStmt = conn.prepareStatement(
                        "SELECT s.*, c.LATITUDE as C_LAT, c.LONGITUDE as C_LON, c.CUSTOMER_CODE, " +
                        "d.LATITUDE as D_LAT, d.LONGITUDE as D_LON, d.DEPOT_CODE " +
                        "FROM ROUTE_STOP_RESULT s " +
                        "LEFT JOIN CUSTOMER c ON s.LOCATION_ID = c.CUSTOMER_ID AND s.ORDER_ID IS NOT NULL " +
                        "LEFT JOIN DEPOT d ON s.LOCATION_ID = d.DEPOT_ID AND s.ORDER_ID IS NULL " +
                        "WHERE s.ROUTE_ID = ? ORDER BY s.SEQUENCE_NO")) {
                        stopStmt.setLong(1, routeId);
                        ResultSet sRs = stopStmt.executeQuery();
                        while (sRs.next()) {
                            Map<String, Object> s = new HashMap<>();
                            s.put("SEQUENCE_NO", sRs.getInt("SEQUENCE_NO"));
                            double cLat = sRs.getDouble("C_LAT");
                            double cLon = sRs.getDouble("C_LON");
                            double dLat = sRs.getDouble("D_LAT");
                            double dLon = sRs.getDouble("D_LON");
                            s.put("LATITUDE", cLat != 0 ? cLat : dLat);
                            s.put("LONGITUDE", cLon != 0 ? cLon : dLon);
                            s.put("CODE", sRs.getString("CUSTOMER_CODE") != null ? sRs.getString("CUSTOMER_CODE") : sRs.getString("DEPOT_CODE"));
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
            return ResponseEntity.internalServerError().build();
        }
        
        return ResponseEntity.ok(response);
    }
}
