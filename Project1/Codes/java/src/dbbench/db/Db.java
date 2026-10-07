package dbbench.db;

import dbbench.util.Args;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Connection details for one database server plus the few places where PostgreSQL and openGauss
 * need different code. Everything else in this package is identical for both systems, which is
 * what makes their measurements comparable.
 */
public final class Db {
    public enum Target { POSTGRES, OPENGAUSS }

    public final Target target;
    private final String host;
    private final int port;
    private final String user;
    private final String password;

    static {
        // The openGauss driver logs every new connection at INFO level; silence it.
        Logger.getLogger("org.opengauss").setLevel(Level.OFF);
    }

    private Db(Target target, String host, int port, String user, String password) {
        this.target = target;
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
    }

    public static Db from(Args a) {
        String t = a.get("target");
        Target target = switch (t) {
            case "pg", "postgres" -> Target.POSTGRES;
            case "og", "opengauss" -> Target.OPENGAUSS;
            default -> throw new IllegalArgumentException("unknown --target " + t);
        };
        return new Db(target, a.get("host"), a.getInt("port", 5432), a.get("user"), a.get("password"));
    }

    /** Name used in result files. */
    public String label() {
        return target == Target.POSTGRES ? "postgres" : "opengauss";
    }

    /** Each system is reached through its own official JDBC driver. */
    public Connection connect(String database) throws SQLException {
        String scheme = target == Target.POSTGRES ? "jdbc:postgresql" : "jdbc:opengauss";
        Properties p = new Properties();
        p.setProperty("user", user);
        p.setProperty("password", password);
        p.setProperty("loggerLevel", "OFF");
        return DriverManager.getConnection(scheme + "://" + host + ":" + port + "/" + database, p);
    }

    /** Bulk load through the COPY protocol; the two drivers expose it under different packages. */
    public long copyIn(Connection c, String sql, InputStream in) throws SQLException, IOException {
        if (target == Target.POSTGRES) {
            return new org.postgresql.copy.CopyManager(c.unwrap(org.postgresql.core.BaseConnection.class))
                    .copyIn(sql, in);
        }
        return new org.opengauss.copy.CopyManager(c.unwrap(org.opengauss.core.BaseConnection.class))
                .copyIn(sql, in);
    }

    /**
     * Both databases are created with UTF-8 and the "C" collation so string comparison is plain
     * byte comparison everywhere, the same as String.equals in the file programs. openGauss also
     * needs PostgreSQL compatibility requested explicitly: its default for a new database is the
     * Oracle-style mode, in which an empty string is NULL.
     */
    public String createDatabaseSql(String name) {
        String sql = "CREATE DATABASE " + name + " WITH TEMPLATE = template0 ENCODING = 'UTF8'"
                + " LC_COLLATE = 'C' LC_CTYPE = 'C'";
        return target == Target.OPENGAUSS ? sql + " DBCOMPATIBILITY = 'PG'" : sql;
    }

    public static void exec(Connection c, String... statements) throws SQLException {
        try (Statement st = c.createStatement()) {
            for (String sql : statements) st.execute(sql);
        }
    }

    public static long queryLong(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    public static String queryString(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** "SQLSTATE: first line of the message", the form in which errors are recorded. */
    public static String describe(SQLException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().strip();
        int nl = msg.indexOf('\n');
        return e.getSQLState() + ": " + (nl < 0 ? msg : msg.substring(0, nl));
    }
}
