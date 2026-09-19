package vn.vrp.algorithm;

import java.util.Locale;
import java.util.Map;

/** Cấu hình chung và parameter động của một lần chạy solver. */
public record SolverOptions(
        long seed,
        int iterationLimit,
        int timeLimitSeconds,
        Map<String, String> parameters) {

    public SolverOptions {
        iterationLimit = iterationLimit <= 0 ? 100 : iterationLimit;
        timeLimitSeconds = Math.max(0, timeLimitSeconds);
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    public int integer(String code, int defaultValue, int min, int max) {
        try {
            int value = Integer.parseInt(value(code, Integer.toString(defaultValue)));
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    public double real(String code, double defaultValue, double min, double max) {
        try {
            double value = Double.parseDouble(value(code, Double.toString(defaultValue)));
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    public boolean bool(String code, boolean defaultValue) {
        String value = value(code, Boolean.toString(defaultValue));
        return value.equals("1") || Boolean.parseBoolean(value);
    }

    private String value(String code, String defaultValue) {
        String normalized = code.toUpperCase(Locale.ROOT);
        return parameters.entrySet().stream()
                .filter(entry -> entry.getKey().toUpperCase(Locale.ROOT).equals(normalized))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(defaultValue);
    }
}
