package vn.vrp.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import vn.vrp.model.Customer;
import vn.vrp.model.Depot;
import vn.vrp.model.ProblemInstance;
import vn.vrp.model.TravelArc;
import vn.vrp.model.TravelTimeBand;
import vn.vrp.model.Vehicle;

/** Đọc một dataset đã chuẩn hóa trong MySQL thành mô hình solver bất biến. */
public final class ProblemInstanceDao {

    public ProblemInstance load(Connection connection, long datasetId) throws SQLException {
        String code;
        String type;
        try (var statement = connection.prepareStatement(
                "SELECT dataset_code, dataset_type FROM dataset WHERE dataset_id = ?")) {
            statement.setLong(1, datasetId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Không tìm thấy dataset_id=" + datasetId);
                }
                code = result.getString("dataset_code");
                type = result.getString("dataset_type");
            }
        }

        Depot depot = loadPrimaryDepot(connection, datasetId);
        List<Customer> customers = loadCustomers(connection, datasetId);
        List<Vehicle> vehicles = loadVehicles(connection, datasetId);
        Map<Long, Map<Long, TravelArc>> arcs = loadTravelArcs(connection, datasetId);

        if (customers.isEmpty()) {
            throw new SQLException("Dataset không có delivery order đang hoạt động");
        }
        if (vehicles.isEmpty()) {
            throw new SQLException("Dataset không có xe đang hoạt động");
        }

        return new ProblemInstance(datasetId, code, type, depot, customers, vehicles, arcs);
    }

    /** Mô hình hiện tại dùng depot active đầu tiên làm depot chung của đội xe. */
    private Depot loadPrimaryDepot(Connection connection, long datasetId) throws SQLException {
        String sql = """
                SELECT depot_id, location_id, depot_code, open_time, close_time
                FROM depot
                WHERE dataset_id = ? AND is_active = 1
                ORDER BY depot_id
                LIMIT 1
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, datasetId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Dataset không có depot đang hoạt động");
                }
                return new Depot(
                        result.getLong("depot_id"),
                        result.getLong("location_id"),
                        result.getString("depot_code"),
                        result.getInt("open_time"),
                        result.getInt("close_time"));
            }
        }
    }

    private List<Customer> loadCustomers(Connection connection, long datasetId)
            throws SQLException {
        List<Customer> customers = new ArrayList<>();
        String sql = """
                SELECT customer_id, order_id, location_id, order_code,
                       demand_weight, demand_volume, service_time,
                       ready_time, due_time, required_vehicle_type, priority
                FROM v_order_problem_input
                WHERE dataset_id = ? AND status <> 'CANCELLED'
                ORDER BY priority DESC, order_id
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, datasetId);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    customers.add(new Customer(
                            result.getLong("customer_id"),
                            result.getLong("order_id"),
                            result.getLong("location_id"),
                            result.getString("order_code"),
                            result.getDouble("demand_weight"),
                            result.getDouble("demand_volume"),
                            result.getInt("service_time"),
                            result.getInt("ready_time"),
                            result.getInt("due_time"),
                            result.getString("required_vehicle_type"),
                            result.getInt("priority")));
                }
            }
        }
        return customers;
    }

    private List<Vehicle> loadVehicles(Connection connection, long datasetId)
            throws SQLException {
        List<Vehicle> vehicles = new ArrayList<>();
        String sql = """
                SELECT vehicle_id, vehicle_code, vehicle_type,
                       capacity_weight, capacity_volume,
                       start_depot_id, end_depot_id,
                       available_from, available_to,
                       fixed_cost, cost_per_km, cost_per_minute
                FROM vehicle
                WHERE dataset_id = ? AND is_active = 1
                ORDER BY vehicle_id
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, datasetId);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    vehicles.add(new Vehicle(
                            result.getLong("vehicle_id"),
                            result.getString("vehicle_code"),
                            result.getString("vehicle_type"),
                            result.getDouble("capacity_weight"),
                            result.getDouble("capacity_volume"),
                            result.getLong("start_depot_id"),
                            result.getLong("end_depot_id"),
                            result.getInt("available_from"),
                            result.getInt("available_to"),
                            result.getDouble("fixed_cost"),
                            result.getDouble("cost_per_km"),
                            result.getDouble("cost_per_minute")));
                }
            }
        }
        return vehicles;
    }

    /**
     * Nạp base arc và các time profile trong một query. Một cung không có profile
     * vẫn được tạo với base_travel_time.
     */
    private Map<Long, Map<Long, TravelArc>> loadTravelArcs(
            Connection connection,
            long datasetId) throws SQLException {
        String sql = """
                SELECT d.distance_id, d.from_location_id, d.to_location_id,
                       d.distance, d.base_travel_time,
                       p.start_time, p.end_time, p.travel_time,
                       p.speed_factor, p.traffic_level
                FROM location_distance d
                LEFT JOIN travel_time_profile p ON p.distance_id = d.distance_id
                WHERE d.dataset_id = ?
                ORDER BY d.distance_id, p.start_time
                """;

        Map<Long, MutableArc> byDistanceId = new LinkedHashMap<>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, datasetId);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    long distanceId = result.getLong("distance_id");
                    MutableArc arc = byDistanceId.computeIfAbsent(
                            distanceId,
                            ignored -> new MutableArc(
                                    getLongUnchecked(result, "from_location_id"),
                                    getLongUnchecked(result, "to_location_id"),
                                    getDoubleUnchecked(result, "distance"),
                                    getIntUnchecked(result, "base_travel_time")));

                    Integer startTime = nullableInt(result, "start_time");
                    if (startTime != null) {
                        arc.timeBands.add(new TravelTimeBand(
                                startTime,
                                result.getInt("end_time"),
                                nullableInt(result, "travel_time"),
                                nullableDouble(result, "speed_factor"),
                                result.getString("traffic_level")));
                    }
                }
            }
        }

        Map<Long, Map<Long, TravelArc>> arcs = new HashMap<>();
        for (MutableArc arc : byDistanceId.values()) {
            arcs.computeIfAbsent(arc.from, ignored -> new HashMap<>())
                    .put(arc.to, new TravelArc(arc.distance, arc.baseTravelTime, arc.timeBands));
        }
        return arcs;
    }

    private static Integer nullableInt(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static Double nullableDouble(ResultSet result, String column) throws SQLException {
        double value = result.getDouble(column);
        return result.wasNull() ? null : value;
    }

    // computeIfAbsent không cho phép checked exception nên các cột NOT NULL được bọc lại.
    private static long getLongUnchecked(ResultSet result, String column) {
        try {
            return result.getLong(column);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static int getIntUnchecked(ResultSet result, String column) {
        try {
            return result.getInt(column);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static double getDoubleUnchecked(ResultSet result, String column) {
        try {
            return result.getDouble(column);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class MutableArc {
        private final long from;
        private final long to;
        private final double distance;
        private final int baseTravelTime;
        private final List<TravelTimeBand> timeBands = new ArrayList<>();

        private MutableArc(long from, long to, double distance, int baseTravelTime) {
            this.from = from;
            this.to = to;
            this.distance = distance;
            this.baseTravelTime = baseTravelTime;
        }
    }
}
