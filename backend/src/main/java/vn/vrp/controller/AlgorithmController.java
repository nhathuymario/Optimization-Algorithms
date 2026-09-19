package vn.vrp.controller;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import vn.vrp.algorithm.AlgorithmRegistry;
import vn.vrp.db.DatabaseConfig;

/** API metadata để FE dựng danh sách thuật toán và form parameter động. */
@RestController
@RequestMapping("/api/algorithms")
@CrossOrigin(origins = "*", methods = {
        RequestMethod.GET,
        RequestMethod.OPTIONS
})
public class AlgorithmController {

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAlgorithms() {
        List<Map<String, Object>> algorithms = new ArrayList<>();
        AlgorithmRegistry registry = new AlgorithmRegistry();
        String algorithmSql = """
                SELECT algorithm_id, algorithm_code, algorithm_name,
                       algorithm_family, algorithm_version, description
                FROM algorithm
                WHERE is_active = 1
                ORDER BY algorithm_id
                """;
        String parameterSql = """
                SELECT parameter_code, parameter_name, data_type,
                       default_value, min_value, max_value, description
                FROM algorithm_parameter
                WHERE algorithm_id = ?
                ORDER BY parameter_id
                """;

        try (Connection connection = DatabaseConfig.from(
                DatabaseConfig.loadProperties()).connect();
                var algorithmStatement = connection.prepareStatement(algorithmSql);
                var algorithmResult = algorithmStatement.executeQuery()) {
            while (algorithmResult.next()) {
                long algorithmId = algorithmResult.getLong("algorithm_id");
                String code = algorithmResult.getString("algorithm_code");
                Map<String, Object> algorithm = new LinkedHashMap<>();
                algorithm.put("algorithmId", algorithmId);
                algorithm.put("code", code);
                algorithm.put("name", algorithmResult.getString("algorithm_name"));
                algorithm.put("family", algorithmResult.getString("algorithm_family"));
                algorithm.put("version", algorithmResult.getString("algorithm_version"));
                algorithm.put("description", algorithmResult.getString("description"));
                algorithm.put("implemented", registry.supports(code));

                List<Map<String, Object>> parameters = new ArrayList<>();
                try (var parameterStatement = connection.prepareStatement(parameterSql)) {
                    parameterStatement.setLong(1, algorithmId);
                    try (var parameterResult = parameterStatement.executeQuery()) {
                        while (parameterResult.next()) {
                            Map<String, Object> parameter = new LinkedHashMap<>();
                            parameter.put("code", parameterResult.getString("parameter_code"));
                            parameter.put("name", parameterResult.getString("parameter_name"));
                            parameter.put("dataType", parameterResult.getString("data_type"));
                            parameter.put("defaultValue", parameterResult.getString("default_value"));
                            parameter.put("minValue", parameterResult.getObject("min_value"));
                            parameter.put("maxValue", parameterResult.getObject("max_value"));
                            parameter.put("description", parameterResult.getString("description"));
                            parameters.add(parameter);
                        }
                    }
                }
                algorithm.put("parameters", parameters);
                algorithms.add(algorithm);
            }
            return ResponseEntity.ok(algorithms);
        } catch (Exception exception) {
            exception.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }
}
