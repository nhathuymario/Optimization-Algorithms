package vn.vrp.model;

/** Profile giao thông áp dụng cho một cung trong khoảng thời gian [start, end). */
public record TravelTimeBand(
        int startTime,
        int endTime,
        Integer travelTime,
        Double speedFactor,
        String trafficLevel) {

    public boolean contains(int secondOfDay) {
        return secondOfDay >= startTime && secondOfDay < endTime;
    }

    public int resolveTravelTime(int baseTravelTime) {
        if (travelTime != null && travelTime > 0) {
            return travelTime;
        }
        if (speedFactor != null && speedFactor > 0) {
            return Math.max(1, (int) Math.round(baseTravelTime / speedFactor));
        }
        return baseTravelTime;
    }
}
