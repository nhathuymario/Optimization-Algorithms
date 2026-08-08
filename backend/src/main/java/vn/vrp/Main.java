package vn.vrp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.CommandLineRunner;

import vn.vrp.algorithm.greedy.GreedyCvrpSolver;
import vn.vrp.db.*;
import vn.vrp.model.*;
import vn.vrp.validator.*;
import java.util.*;

@SpringBootApplication
public class Main {
    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    @Bean
    public CommandLineRunner runDemo() {
        return args -> {
            System.out.println("====== VRP Spring Boot API Bắt Đầu ======");
            Properties props = DatabaseConfig.loadProperties();
            long datasetId = args.length > 0 ? Long.parseLong(args[0]) : Long.parseLong(props.getProperty("dataset.id", "1"));
            long seed = Long.parseLong(props.getProperty("random.seed", "20260804"));
            
            DatabaseConfig db = DatabaseConfig.from(props);
            try (var connection = db.connect()) {
                ProblemInstance instance = new ProblemInstanceDao().load(connection, datasetId);
                Solution solution = new GreedyCvrpSolver().solve(instance);
                ValidationResult validation = new SolutionValidator().validate(instance, solution);
                print(instance, solution, validation);
                
                if (validation.valid()) {
                    long experimentId = new ExperimentDao().save(connection, instance, solution, validation, seed);
                    System.out.println("Đã lưu tự động thuật toán mẫu với experiment_id=" + experimentId);
                }
            } catch (Exception e) {
                System.err.println("Lỗi khi chạy thuật toán mẫu (Demo): " + e.getMessage());
            }
        };
    }

    private static void print(ProblemInstance p, Solution s, ValidationResult v) {
        System.out.printf("Dataset: %s (%s), %d orders, %d vehicles%n", p.code(), p.type(), p.customers().size(), p.vehicles().size());
        int n = 1;
        for (Route r : s.routes()) {
            System.out.printf("Route %d [%s]: %s | load=%.2f | distance=%.2f%n", n++, r.vehicle().code(), r.customers().stream().map(Customer::code).toList(), r.totalLoadWeight(), r.totalDistance());
        }
        System.out.printf("Total: vehicles=%d, distance=%.2f, cost=%.2f, valid=%s%n", s.routes().size(), s.totalDistance(), s.totalCost(), v.valid());
    }
}
