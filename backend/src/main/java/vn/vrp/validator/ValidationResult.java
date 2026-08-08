package vn.vrp.validator;
import java.util.List;
public record ValidationResult(boolean valid, List<String> errors, int unservedOrders,
                               double capacityViolation, int timeWindowViolation) {
    public ValidationResult { errors = List.copyOf(errors); }
}
