package vn.vrp.model;
public record Vehicle(long id, String code, String type, double capacityWeight,
                      double capacityVolume, long startDepotId, long endDepotId,
                      int availableFrom, int availableTo, double fixedCost,
                      double costPerKm, double costPerMinute) {}
