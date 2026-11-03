package dev.workhub.contract;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class ContractSupport {
    private ContractSupport() { }
    public static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
    public static String required(String name) {
        String value = env(name, "");
        if (value.isBlank()) throw new IllegalStateException("Set " + name + " locally; do not commit credentials");
        return value;
    }
    public static Connection mysql() throws SQLException {
        String db = env("MYSQL_DATABASE", "workhub") + "_contract";
        return DriverManager.getConnection("jdbc:mysql://127.0.0.1:" + env("MYSQL_PORT", "13306")
                + "/" + db + "?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC",
                env("MYSQL_USER", "workhub"), required("MYSQL_PASSWORD"));
    }
}
