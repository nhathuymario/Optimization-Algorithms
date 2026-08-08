package vn.vrp.model;
public record RouteStop(Type type, long locationId, Long orderId, int arrivalTime,
                        int serviceStartTime, int departureTime, int waitingTime,
                        int serviceTime, double demandWeight, double demandVolume,
                        double loadBeforeWeight, double loadAfterWeight,
                        double loadBeforeVolume, double loadAfterVolume,
                        double travelDistance, int travelTime,
                        double capacityViolation, int timeWindowViolation) {
    public enum Type { DEPOT_START, CUSTOMER, DEPOT_END }
}
