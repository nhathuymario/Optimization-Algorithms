package vn.vrp.db;
import java.io.*; import java.nio.file.*; import java.sql.*; import java.util.*;
public record DatabaseConfig(String url, String user, String password) {
    public static Properties loadProperties() {
        Properties p = new Properties();
        try (InputStream in = DatabaseConfig.class.getResourceAsStream("/application.properties")) {
            if (in == null) throw new IllegalStateException("Không tìm thấy application.properties");
            p.load(in);
        } catch (IOException e) { throw new UncheckedIOException(e); }
        Path local = Path.of("application-local.properties");
        if (Files.isRegularFile(local)) try (InputStream in = Files.newInputStream(local)) { p.load(in); }
        catch (IOException e) { throw new UncheckedIOException(e); }
        p.replaceAll((k,v) -> expand(v.toString()));
        return p;
    }
    private static String expand(String value) {
        var m = java.util.regex.Pattern.compile("\\$\\{([^:}]+):([^}]*)}").matcher(value);
        StringBuffer out = new StringBuffer();
        while (m.find()) m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(System.getenv().getOrDefault(m.group(1), m.group(2))));
        return m.appendTail(out).toString();
    }
    public static DatabaseConfig from(Properties p) { return new DatabaseConfig(p.getProperty("db.url"), p.getProperty("db.user"), p.getProperty("db.password")); }
    public Connection connect() throws SQLException { return DriverManager.getConnection(url, user, password); }
}
