package vn.vrp.model;
import java.util.*;
public record ProblemInstance(long datasetId, String code, String type, Depot depot,
                              List<Customer> customers, List<Vehicle> vehicles,
                              Map<Long, Map<Long, TravelArc>> arcs) {
    public ProblemInstance {
        customers = List.copyOf(customers); vehicles = List.copyOf(vehicles);
        arcs = arcs.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, e -> Map.copyOf(e.getValue())));
    }
    public TravelArc arc(long from, long to) {
        if (from == to) return TravelArc.ZERO;
        TravelArc arc = arcs.getOrDefault(from, Map.of()).get(to);
        if (arc == null) throw new IllegalStateException("Thiếu cạnh khoảng cách " + from + " -> " + to);
        return arc;
    }
}
