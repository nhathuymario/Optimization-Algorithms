package vn.vrp.model;

import java.util.Comparator;
import java.util.List;

/** Cung di chuyển cùng các profile thời gian tùy theo giờ xuất phát. */
public record TravelArc(double distance, int travelTime, List<TravelTimeBand> timeBands) {
    public static final TravelArc ZERO = new TravelArc(0, 0, List.of());

    public TravelArc(double distance, int travelTime) {
        this(distance, travelTime, List.of());
    }

    public TravelArc {
        timeBands = timeBands == null
                ? List.of()
                : timeBands.stream()
                        .sorted(Comparator.comparingInt(TravelTimeBand::startTime))
                        .toList();
    }

    /**
     * Trả thời gian di chuyển tại thời điểm xuất phát; dùng base time nếu không có profile.
     */
    public int travelTimeAt(int departureTime) {
        int secondOfDay = Math.floorMod(departureTime, 86_400);
        return timeBands.stream()
                .filter(band -> band.contains(secondOfDay))
                .findFirst()
                .map(band -> band.resolveTravelTime(travelTime))
                .orElse(travelTime);
    }
}
