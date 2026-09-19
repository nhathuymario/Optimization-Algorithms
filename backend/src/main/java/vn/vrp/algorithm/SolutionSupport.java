package vn.vrp.algorithm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import vn.vrp.model.Customer;
import vn.vrp.model.ProblemInstance;
import vn.vrp.model.Route;
import vn.vrp.model.RouteStop;
import vn.vrp.model.Solution;
import vn.vrp.model.TravelArc;
import vn.vrp.model.Vehicle;

/** Các phép dựng và đánh giá nghiệm dùng chung cho Greedy/Tabu/GA/ACO. */
public final class SolutionSupport {
    private static final double EPS = 1e-9;

    private SolutionSupport() {}

    public static double objective(Solution solution) {
        // Lexicographic vehicle count/distance được mã hóa bằng hệ số đủ lớn.
        return solution.routes().size() * 100_000d + solution.totalDistance();
    }

    public static boolean better(Solution left, Solution right) {
        if (left.feasible() != right.feasible()) {
            return left.feasible();
        }
        if (left.routes().size() != right.routes().size()) {
            return left.routes().size() < right.routes().size();
        }
        return left.totalDistance() + EPS < right.totalDistance();
    }

    /** Thay execution time của nghiệm tốt nhất bằng tổng thời gian chạy thuật toán. */
    public static Solution finish(Solution solution, long startedNanos) {
        return new Solution(
                solution.routes(),
                solution.totalDistance(),
                solution.totalTravelTime(),
                solution.totalWaitingTime(),
                solution.totalServiceTime(),
                solution.totalCost(),
                Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000),
                solution.feasible());
    }

    /** Dựng Solution từ danh sách customer ứng với từng vị trí vehicle trong problem. */
    public static Solution buildSolution(
            ProblemInstance problem,
            List<List<Customer>> assignments,
            long startedNanos) {
        List<Route> routes = new ArrayList<>();
        for (int index = 0; index < assignments.size() && index < problem.vehicles().size(); index++) {
            List<Customer> customers = assignments.get(index);
            if (!customers.isEmpty()) {
                routes.add(buildRoute(problem, problem.vehicles().get(index), customers));
            }
        }

        double distance = routes.stream().mapToDouble(Route::totalDistance).sum();
        int travel = routes.stream().mapToInt(Route::totalTravelTime).sum();
        int waiting = routes.stream().mapToInt(Route::totalWaitingTime).sum();
        int service = routes.stream().mapToInt(Route::totalServiceTime).sum();
        double cost = routes.stream()
                .mapToDouble(route -> route.vehicle().fixedCost()
                        + route.totalDistance() * route.vehicle().costPerKm()
                        + (route.totalTravelTime()
                                + route.totalWaitingTime()
                                + route.totalServiceTime())
                                / 60.0 * route.vehicle().costPerMinute())
                .sum();

        Set<Long> served = new HashSet<>();
        routes.forEach(route -> route.customers().forEach(customer -> served.add(customer.orderId())));
        boolean feasible = served.size() == problem.customers().size()
                && routes.stream().allMatch(Route::feasible);

        return new Solution(
                routes,
                distance,
                travel,
                waiting,
                service,
                cost,
                Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000),
                feasible);
    }

    /** Kiểm tra nhanh việc nối thêm một customer vào cuối route. */
    public static boolean canAppend(
            ProblemInstance problem,
            Vehicle vehicle,
            List<Customer> prefix,
            Customer candidate) {
        if (!candidate.accepts(vehicle)) {
            return false;
        }
        List<Customer> tentative = new ArrayList<>(prefix);
        tentative.add(candidate);
        return buildRoute(problem, vehicle, tentative).feasible();
    }

    /**
     * Split một giant tour thành các route liên tiếp theo thứ tự đội xe.
     * Trả empty khi permutation không thể phân thành nghiệm khả thi.
     */
    public static Optional<Solution> decodePermutation(
            ProblemInstance problem,
            List<Customer> permutation,
            long startedNanos) {
        List<List<Customer>> assignments = emptyAssignments(problem);
        int vehicleIndex = 0;

        for (Customer customer : permutation) {
            while (vehicleIndex < problem.vehicles().size()
                    && !canAppend(
                            problem,
                            problem.vehicles().get(vehicleIndex),
                            assignments.get(vehicleIndex),
                            customer)) {
                vehicleIndex++;
            }
            if (vehicleIndex >= problem.vehicles().size()) {
                return Optional.empty();
            }
            assignments.get(vehicleIndex).add(customer);
        }

        Solution solution = buildSolution(problem, assignments, startedNanos);
        return solution.feasible() ? Optional.of(solution) : Optional.empty();
    }

    public static List<List<Customer>> emptyAssignments(ProblemInstance problem) {
        List<List<Customer>> assignments = new ArrayList<>();
        problem.vehicles().forEach(ignored -> assignments.add(new ArrayList<>()));
        return assignments;
    }

    /** Chuyển Solution về assignments theo đúng vehicle index của ProblemInstance. */
    public static List<List<Customer>> assignmentsFrom(
            ProblemInstance problem,
            Solution solution) {
        List<List<Customer>> assignments = emptyAssignments(problem);
        for (Route route : solution.routes()) {
            for (int index = 0; index < problem.vehicles().size(); index++) {
                if (problem.vehicles().get(index).id() == route.vehicle().id()) {
                    assignments.get(index).addAll(route.customers());
                    break;
                }
            }
        }
        return assignments;
    }

    public static List<List<Customer>> copyAssignments(List<List<Customer>> source) {
        return source.stream().map(ArrayList::new).map(list -> (List<Customer>) list).toList();
    }

    /** Tính đầy đủ timeline, tải và vi phạm của một route. */
    public static Route buildRoute(
            ProblemInstance problem,
            Vehicle vehicle,
            List<Customer> customers) {
        List<RouteStop> stops = new ArrayList<>();
        int time = Math.max(problem.depot().openTime(), vehicle.availableFrom());
        int start = time;
        int travel = 0;
        int waiting = 0;
        int service = 0;
        double distance = 0;
        double loadWeight = customers.stream().mapToDouble(Customer::demandWeight).sum();
        double loadVolume = customers.stream().mapToDouble(Customer::demandVolume).sum();
        double remainingWeight = loadWeight;
        double remainingVolume = loadVolume;
        long currentLocation = problem.depot().locationId();
        boolean feasible = loadWeight <= vehicle.capacityWeight() + EPS
                && (vehicle.capacityVolume() <= EPS
                || loadVolume <= vehicle.capacityVolume() + EPS)
                && time <= Math.min(problem.depot().closeTime(), vehicle.availableTo());

        stops.add(new RouteStop(
                RouteStop.Type.DEPOT_START,
                currentLocation,
                null,
                time,
                time,
                time,
                0,
                0,
                0,
                0,
                0,
                remainingWeight,
                0,
                remainingVolume,
                0,
                0,
                0,
                0));

        for (Customer customer : customers) {
            TravelArc arc = problem.arc(currentLocation, customer.locationId());
            int arcTravelTime = arc.travelTimeAt(time);
            int arrival = time + arcTravelTime;
            int serviceStart = Math.max(arrival, customer.readyTime());
            int wait = serviceStart - arrival;
            int departure = serviceStart + customer.serviceTime();
            int timeWindowViolation = Math.max(0, serviceStart - customer.dueTime());
            double capacityViolation = Math.max(0, loadWeight - vehicle.capacityWeight());
            if (vehicle.capacityVolume() > EPS) {
                capacityViolation += Math.max(0, loadVolume - vehicle.capacityVolume());
            }

            stops.add(new RouteStop(
                    RouteStop.Type.CUSTOMER,
                    customer.locationId(),
                    customer.orderId(),
                    arrival,
                    serviceStart,
                    departure,
                    wait,
                    customer.serviceTime(),
                    customer.demandWeight(),
                    customer.demandVolume(),
                    remainingWeight,
                    remainingWeight - customer.demandWeight(),
                    remainingVolume,
                    remainingVolume - customer.demandVolume(),
                    arc.distance(),
                    arcTravelTime,
                    capacityViolation,
                    timeWindowViolation));

            distance += arc.distance();
            travel += arcTravelTime;
            waiting += wait;
            service += customer.serviceTime();
            time = departure;
            currentLocation = customer.locationId();
            remainingWeight -= customer.demandWeight();
            remainingVolume -= customer.demandVolume();
            feasible &= customer.accepts(vehicle) && timeWindowViolation == 0;
        }

        TravelArc returnArc = problem.arc(currentLocation, problem.depot().locationId());
        int returnTravelTime = returnArc.travelTimeAt(time);
        int end = time + returnTravelTime;
        int routeEndLimit = Math.min(problem.depot().closeTime(), vehicle.availableTo());
        int endViolation = Math.max(0, end - routeEndLimit);
        distance += returnArc.distance();
        travel += returnTravelTime;
        feasible &= endViolation == 0;

        stops.add(new RouteStop(
                RouteStop.Type.DEPOT_END,
                problem.depot().locationId(),
                null,
                end,
                end,
                end,
                0,
                0,
                0,
                0,
                remainingWeight,
                0,
                remainingVolume,
                0,
                returnArc.distance(),
                returnTravelTime,
                0,
                endViolation));

        return new Route(
                vehicle,
                customers,
                stops,
                distance,
                travel,
                waiting,
                service,
                loadWeight,
                loadVolume,
                start,
                end,
                feasible);
    }
}
