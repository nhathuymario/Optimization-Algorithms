package vn.vrp.db;
import vn.vrp.model.*; import java.sql.*; import java.util.*;
public final class ProblemInstanceDao {
    public ProblemInstance load(Connection c, long datasetId) throws SQLException {
        String code, type;
        try (var ps=c.prepareStatement("SELECT dataset_code,dataset_type FROM dataset WHERE dataset_id=?")) { ps.setLong(1,datasetId); try(var rs=ps.executeQuery()){ if(!rs.next()) throw new SQLException("Không tìm thấy dataset_id="+datasetId); code=rs.getString(1); type=rs.getString(2); }}
        Depot depot;
        try(var ps=c.prepareStatement("SELECT depot_id,location_id,depot_code,open_time,close_time FROM depot WHERE dataset_id=? AND is_active=1 ORDER BY depot_id LIMIT 1")){ps.setLong(1,datasetId);try(var rs=ps.executeQuery()){if(!rs.next())throw new SQLException("Dataset không có depot");depot=new Depot(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getInt(4),rs.getInt(5));}}
        List<Customer> customers=new ArrayList<>();
        String cq="SELECT customer_id,order_id,location_id,order_code,demand_weight,demand_volume,service_time,ready_time,due_time FROM v_order_problem_input WHERE dataset_id=? AND status<>'CANCELLED' ORDER BY order_id";
        try(var ps=c.prepareStatement(cq)){ps.setLong(1,datasetId);try(var rs=ps.executeQuery()){while(rs.next())customers.add(new Customer(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getString(4),rs.getDouble(5),rs.getDouble(6),rs.getInt(7),rs.getInt(8),rs.getInt(9)));}}
        List<Vehicle> vehicles=new ArrayList<>();
        String vq="SELECT vehicle_id,vehicle_code,vehicle_type,capacity_weight,capacity_volume,start_depot_id,end_depot_id,available_from,available_to,fixed_cost,cost_per_km,cost_per_minute FROM vehicle WHERE dataset_id=? AND is_active=1 ORDER BY vehicle_id";
        try(var ps=c.prepareStatement(vq)){ps.setLong(1,datasetId);try(var rs=ps.executeQuery()){while(rs.next())vehicles.add(new Vehicle(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getDouble(4),rs.getDouble(5),rs.getLong(6),rs.getLong(7),rs.getInt(8),rs.getInt(9),rs.getDouble(10),rs.getDouble(11),rs.getDouble(12)));}}
        Map<Long,Map<Long,TravelArc>> arcs=new HashMap<>();
        try(var ps=c.prepareStatement("SELECT from_location_id,to_location_id,distance,base_travel_time FROM location_distance WHERE dataset_id=?")){ps.setLong(1,datasetId);try(var rs=ps.executeQuery()){while(rs.next())arcs.computeIfAbsent(rs.getLong(1),k->new HashMap<>()).put(rs.getLong(2),new TravelArc(rs.getDouble(3),rs.getInt(4)));}}
        if(customers.isEmpty())throw new SQLException("Dataset không có delivery order"); if(vehicles.isEmpty())throw new SQLException("Dataset không có xe");
        return new ProblemInstance(datasetId,code,type,depot,customers,vehicles,arcs);
    }
}
