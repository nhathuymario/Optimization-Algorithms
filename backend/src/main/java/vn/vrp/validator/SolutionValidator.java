package vn.vrp.validator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import vn.vrp.model.Customer;
import vn.vrp.model.ProblemInstance;
import vn.vrp.model.Route;
import vn.vrp.model.RouteStop;
import vn.vrp.model.Solution;
import vn.vrp.model.TravelArc;
import vn.vrp.model.Vehicle;

/** Tính lại độc lập các ràng buộc và KPI, không tin vào số liệu đã lưu trong Solution. */
public final class SolutionValidator {
    private static final double EPS = 1e-6;

    public ValidationResult validate(ProblemInstance problem, Solution solution) {
        List<String> errors = new ArrayList<>();
        Map<Long, Customer> expectedByOrder = problem.customers().stream()
                .collect(Collectors.toMap(Customer::orderId, Function.identity()));
        Map<Long, Vehicle> expectedVehicles = problem.vehicles().stream()
                .collect(Collectors.toMap(Vehicle::id, Function.identity()));

        if (solution.routes().size() > problem.vehicles().size()) {
            errors.add("Số route vượt số xe hiện có");
        }

        Map<Long, Long> visits = solution.routes().stream()
                .flatMap(route -> route.customers().stream())
                .map(Customer::orderId)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        for (long orderId : expectedByOrder.keySet()) {
            long count = visits.getOrDefault(orderId, 0L);
            if (count != 1) {
                errors.add("Order " + orderId + " được phục vụ " + count + " lần");
            }
        }
        for (long orderId : visits.keySet()) {
            if (!expectedByOrder.containsKey(orderId)) {
                errors.add("Order không thuộc dataset: " + orderId);
            }
        }

        Set<Long> usedVehicles = new HashSet<>();
        double totalCapacityViolation = 0;
        int totalTimeWindowViolation = 0;
        double calculatedSolutionDistance = 0;
        int calculatedSolutionTravel = 0;
        int calculatedSolutionWaiting = 0;
        int calculatedSolutionService = 0;

        for (Route route : solution.routes()) {
            Vehicle vehicle = expectedVehicles.get(route.vehicle().id());
            if (vehicle == null) {
                errors.add("Xe " + route.vehicle().id() + " không thuộc dataset");
                vehicle = route.vehicle();
            }
            if (!usedVehicles.add(vehicle.id())) {
                errors.add("Xe " + vehicle.code() + " được dùng cho nhiều route");
            }

            RouteCheck check = validateRoute(
                    problem,
                    route,
                    vehicle,
                    expectedByOrder,
                    errors);
            totalCapacityViolation += check.capacityViolation();
            totalTimeWindowViolation += check.timeWindowViolation();
            calculatedSolutionDistance += check.distance();
            calculatedSolutionTravel += check.travelTime();
            calculatedSolutionWaiting += check.waitingTime();
            calculatedSolutionService += check.serviceTime();
        }

        compareDouble("Tổng khoảng cách solution", calculatedSolutionDistance,
                solution.totalDistance(), errors);
        compareInt("Tổng travel time solution", calculatedSolutionTravel,
                solution.totalTravelTime(), errors);
        compareInt("Tổng waiting time solution", calculatedSolutionWaiting,
                solution.totalWaitingTime(), errors);
        compareInt("Tổng service time solution", calculatedSolutionService,
                solution.totalServiceTime(), errors);

        int unservedOrders = (int) expectedByOrder.keySet().stream()
                .filter(orderId -> visits.getOrDefault(orderId, 0L) == 0)
                .count();

        boolean calculatedFeasible = errors.isEmpty();
        if (solution.feasible() != calculatedFeasible) {
            errors.add("Cờ feasible của solution không khớp kết quả validator");
        }
        return new ValidationResult(
                errors.isEmpty(),
                errors,
                unservedOrders,
                totalCapacityViolation,
                totalTimeWindowViolation);
    }

    private RouteCheck validateRoute(
            ProblemInstance problem,
            Route route,
            Vehicle vehicle,
            Map<Long, Customer> expectedByOrder,
            List<String> errors) {
        String prefix = "Route xe " + vehicle.code() + ": ";
        double loadWeight = route.customers().stream().mapToDouble(Customer::demandWeight).sum();
        double loadVolume = route.customers().stream().mapToDouble(Customer::demandVolume).sum();
        double capacityViolation = Math.max(0, loadWeight - vehicle.capacityWeight());
        if (vehicle.capacityVolume() > EPS) {
            capacityViolation += Math.max(0, loadVolume - vehicle.capacityVolume());
        }
        if (capacityViolation > EPS) {
            errors.add(prefix + "vượt tải " + capacityViolation);
        }
        compareDouble(prefix + "totalLoadWeight", loadWeight, route.totalLoadWeight(), errors);
        compareDouble(prefix + "totalLoadVolume", loadVolume, route.totalLoadVolume(), errors);

        for (Customer customer : route.customers()) {
            Customer expected = expectedByOrder.get(customer.orderId());
            if (expected != null && expected.locationId() != customer.locationId()) {
                errors.add(prefix + "order " + customer.orderId() + " sai location");
            }
            if (!customer.accepts(vehicle)) {
                errors.add(prefix + "order " + customer.orderId()
                        + " yêu cầu loại xe " + customer.requiredVehicleType());
            }
        }

        if (route.stops().size() != route.customers().size() + 2) {
            errors.add(prefix + "số stop không khớp số customer");
            return new RouteCheck(
                    route.totalDistance(),
                    route.totalTravelTime(),
                    route.totalWaitingTime(),
                    route.totalServiceTime(),
                    capacityViolation,
                    route.stops().stream().mapToInt(RouteStop::timeWindowViolation).sum());
        }

        RouteStop first = route.stops().getFirst();
        RouteStop last = route.stops().getLast();
        if (first.type() != RouteStop.Type.DEPOT_START
                || first.locationId() != problem.depot().locationId()) {
            errors.add(prefix + "không bắt đầu tại depot của dataset");
        }
        if (last.type() != RouteStop.Type.DEPOT_END
                || last.locationId() != problem.depot().locationId()) {
            errors.add(prefix + "không kết thúc tại depot của dataset");
        }

        int time = Math.max(problem.depot().openTime(), vehicle.availableFrom());
        compareInt(prefix + "startTime", time, route.startTime(), errors);
        validateStopTime(prefix + "depot start", first, time, time, time, 0, 0, errors);

        long currentLocation = problem.depot().locationId();
        double remainingWeight = loadWeight;
        double remainingVolume = loadVolume;
        double distance = 0;
        int travel = 0;
        int waiting = 0;
        int service = 0;
        int timeWindowViolation = 0;
        boolean calculatedRouteFeasible = capacityViolation <= EPS;

        for (int index = 0; index < route.customers().size(); index++) {
            Customer customer = route.customers().get(index);
            RouteStop stop = route.stops().get(index + 1);
            String stopPrefix = prefix + "stop " + (index + 1) + ": ";
            if (stop.type() != RouteStop.Type.CUSTOMER
                    || stop.orderId() == null
                    || stop.orderId() != customer.orderId()
                    || stop.locationId() != customer.locationId()) {
                errors.add(stopPrefix + "không khớp thứ tự customer của route");
            }

            TravelArc arc = problem.arc(currentLocation, customer.locationId());
            int arcTravelTime = arc.travelTimeAt(time);
            int arrival = time + arcTravelTime;
            int serviceStart = Math.max(arrival, customer.readyTime());
            int wait = serviceStart - arrival;
            int departure = serviceStart + customer.serviceTime();
            int violation = Math.max(0, serviceStart - customer.dueTime());
            validateStopTime(
                    stopPrefix,
                    stop,
                    arrival,
                    serviceStart,
                    departure,
                    wait,
                    customer.serviceTime(),
                    errors);
            compareDouble(stopPrefix + "travelDistance", arc.distance(), stop.travelDistance(), errors);
            compareInt(stopPrefix + "travelTime", arcTravelTime, stop.travelTime(), errors);
            compareDouble(stopPrefix + "loadBeforeWeight", remainingWeight,
                    stop.loadBeforeWeight(), errors);
            compareDouble(stopPrefix + "loadAfterWeight",
                    remainingWeight - customer.demandWeight(), stop.loadAfterWeight(), errors);
            compareDouble(stopPrefix + "loadBeforeVolume", remainingVolume,
                    stop.loadBeforeVolume(), errors);
            compareDouble(stopPrefix + "loadAfterVolume",
                    remainingVolume - customer.demandVolume(), stop.loadAfterVolume(), errors);
            compareInt(stopPrefix + "timeWindowViolation", violation,
                    stop.timeWindowViolation(), errors);

            distance += arc.distance();
            travel += arcTravelTime;
            waiting += wait;
            service += customer.serviceTime();
            timeWindowViolation += violation;
            calculatedRouteFeasible &= violation == 0 && customer.accepts(vehicle);
            time = departure;
            currentLocation = customer.locationId();
            remainingWeight -= customer.demandWeight();
            remainingVolume -= customer.demandVolume();
        }

        TravelArc returnArc = problem.arc(currentLocation, problem.depot().locationId());
        int returnTravelTime = returnArc.travelTimeAt(time);
        int end = time + returnTravelTime;
        int endViolation = Math.max(
                0,
                end - Math.min(problem.depot().closeTime(), vehicle.availableTo()));
        validateStopTime(prefix + "depot end", last, end, end, end, 0, 0, errors);
        compareDouble(prefix + "return distance", returnArc.distance(), last.travelDistance(), errors);
        compareInt(prefix + "return travel time", returnTravelTime, last.travelTime(), errors);
        compareInt(prefix + "end violation", endViolation, last.timeWindowViolation(), errors);
        distance += returnArc.distance();
        travel += returnTravelTime;
        timeWindowViolation += endViolation;
        calculatedRouteFeasible &= endViolation == 0;

        compareDouble(prefix + "totalDistance", distance, route.totalDistance(), errors);
        compareInt(prefix + "totalTravelTime", travel, route.totalTravelTime(), errors);
        compareInt(prefix + "totalWaitingTime", waiting, route.totalWaitingTime(), errors);
        compareInt(prefix + "totalServiceTime", service, route.totalServiceTime(), errors);
        compareInt(prefix + "endTime", end, route.endTime(), errors);
        if (route.feasible() != calculatedRouteFeasible) {
            errors.add(prefix + "cờ feasible không khớp lịch trình tính lại");
        }

        return new RouteCheck(
                distance,
                travel,
                waiting,
                service,
                capacityViolation,
                timeWindowViolation);
    }

    private void validateStopTime(
            String prefix,
            RouteStop stop,
            int arrival,
            int serviceStart,
            int departure,
            int waiting,
            int service,
            List<String> errors) {
        compareInt(prefix + " arrival", arrival, stop.arrivalTime(), errors);
        compareInt(prefix + " serviceStart", serviceStart, stop.serviceStartTime(), errors);
        compareInt(prefix + " departure", departure, stop.departureTime(), errors);
        compareInt(prefix + " waiting", waiting, stop.waitingTime(), errors);
        compareInt(prefix + " service", service, stop.serviceTime(), errors);
    }

    private static void compareDouble(
            String field,
            double expected,
            double actual,
            List<String> errors) {
        if (Math.abs(expected - actual) > EPS) {
            errors.add(field + " sai: expected=" + expected + ", actual=" + actual);
        }
    }

    private static void compareInt(
            String field,
            int expected,
            int actual,
            List<String> errors) {
        if (expected != actual) {
            errors.add(field + " sai: expected=" + expected + ", actual=" + actual);
        }
    }

    private record RouteCheck(
            double distance,
            int travelTime,
            int waitingTime,
            int serviceTime,
            double capacityViolation,
            int timeWindowViolation) {}
}
