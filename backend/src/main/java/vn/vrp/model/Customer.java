package vn.vrp.model;
public record Customer(long customerId, long orderId, long locationId, String code,
                       double demandWeight, double demandVolume, int serviceTime,
                       int readyTime, int dueTime) {}
