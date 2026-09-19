package vn.vrp.algorithm;

/** Một điểm dữ liệu dùng để lưu và vẽ quá trình hội tụ của thuật toán. */
public record IterationSnapshot(
        int iteration,
        long elapsedTimeMs,
        double currentObjective,
        double bestObjective,
        int currentVehicleUsed,
        int bestVehicleUsed,
        double currentDistance,
        double bestDistance,
        int feasibleSolutionCount,
        Double diversityValue,
        String notes) {}
