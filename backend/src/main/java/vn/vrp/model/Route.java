package vn.vrp.model;
import java.util.List;
public record Route(Vehicle vehicle, List<Customer> customers, List<RouteStop> stops,
                    double totalDistance, int totalTravelTime, int totalWaitingTime,
                    int totalServiceTime, double totalLoadWeight, double totalLoadVolume,
                    int startTime, int endTime, boolean feasible) {
    public Route { customers = List.copyOf(customers); stops = List.copyOf(stops); }
}
