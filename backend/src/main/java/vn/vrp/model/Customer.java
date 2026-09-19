package vn.vrp.model;

/** Một delivery order đã được ghép với customer/location để solver sử dụng trực tiếp. */
public record Customer(
        long customerId,
        long orderId,
        long locationId,
        String code,
        double demandWeight,
        double demandVolume,
        int serviceTime,
        int readyTime,
        int dueTime,
        String requiredVehicleType,
        int priority) {

    /** Giữ tương thích với fixture/code cũ chưa truyền loại xe và độ ưu tiên. */
    public Customer(
            long customerId,
            long orderId,
            long locationId,
            String code,
            double demandWeight,
            double demandVolume,
            int serviceTime,
            int readyTime,
            int dueTime) {
        this(customerId, orderId, locationId, code, demandWeight, demandVolume,
                serviceTime, readyTime, dueTime, null, 0);
    }

    /** Kiểm tra order có yêu cầu đúng loại xe đang xét hay không. */
    public boolean accepts(Vehicle vehicle) {
        return requiredVehicleType == null
                || requiredVehicleType.isBlank()
                || requiredVehicleType.equalsIgnoreCase(vehicle.type());
    }
}
