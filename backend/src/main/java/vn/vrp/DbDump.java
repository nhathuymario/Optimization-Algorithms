package vn.vrp;

import java.sql.*;
import vn.vrp.db.DatabaseConfig;

public class DbDump {
    public static void main(String[] args) throws Exception {
        try (Connection c = DatabaseConfig.from(DatabaseConfig.loadProperties()).connect()) {
            System.out.println("=== TABLES ===");
            try (ResultSet rs = c.createStatement().executeQuery("SHOW TABLES")) {
                while (rs.next()) {
                    System.out.println(rs.getString(1));
                }
            }
        }
    }
}
