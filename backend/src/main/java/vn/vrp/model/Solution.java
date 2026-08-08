package vn.vrp.model;
import java.util.List;
public record Solution(List<Route> routes, double totalDistance, int totalTravelTime,
                       int totalWaitingTime, int totalServiceTime, double totalCost,
                       long executionTimeMs, boolean feasible) {
    public Solution { routes = List.copyOf(routes); }
}
