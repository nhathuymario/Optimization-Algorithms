package vn.vrp.model;
public record TravelArc(double distance, int travelTime) {
    public static final TravelArc ZERO = new TravelArc(0, 0);
}
