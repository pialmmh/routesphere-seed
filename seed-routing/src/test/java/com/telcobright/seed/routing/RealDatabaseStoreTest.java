package com.telcobright.seed.routing;

import com.telcobright.seed.routing.policy.PolicyConflictException;
import com.telcobright.seed.routing.policy.PolicyTypes;
import com.telcobright.seed.routing.policy.RoutingPolicy;
import com.telcobright.seed.routing.store.JdbcPolicyStore;
import com.telcobright.seed.routing.store.PolicyCatalog;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JSON column FOR REAL: PostgreSQL {@code jsonb} and MySQL {@code json}. Opt-in — it runs only when a URL is
 * given, against a THROWAWAY database (never a shared one):
 *
 * <pre>
 *   mvn test -Dseed.routing.pg.url="jdbc:postgresql://127.0.0.1:7432/routing?user=routing&amp;password=…"
 *            -Dseed.routing.mysql.url="jdbc:mysql://127.0.0.1:7306/routing?user=root&amp;password=…"
 * </pre>
 */
class RealDatabaseStoreTest {
    private static final String DOC = """
        { "schema": 1, "rules": [ { "name": "btcl-wifi-retail", "priority": 100,
            "match": { "partner": "btcl", "app": "wifi-retail", "zone": "*" }, "routes": [ { "route": "bkash", "weight": 100 } ] } ],
          "default": { "reject": "no-rule-matched" } }""";

    @Test
    void postgresJsonb() throws Exception { run(System.getProperty("seed.routing.pg.url"), JdbcPolicyStore.Dialect.POSTGRES, "jsonb"); }

    @Test
    void mysqlJson() throws Exception { run(System.getProperty("seed.routing.mysql.url"), JdbcPolicyStore.Dialect.MYSQL, "json"); }

    private static void run(String url, JdbcPolicyStore.Dialect expected, String columnType) throws Exception {
        Assumptions.assumeTrue(url != null && !url.isBlank(), "no database URL given — skipped");
        DataSource ds = new UrlDataSource(url);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS routing_policy_history");
            st.execute("DROP TABLE IF EXISTS routing_policy");
        }
        JdbcPolicyStore store = new JdbcPolicyStore(ds).ensureSchema().ensureSchema();
        assertEquals(expected, store.dialect());

        RoutingPolicy v1 = store.save(RoutingPolicy.draft("payment", "btcl-wifi-retail", "match-loadbalance", "BTCL public WiFi", DOC), "test");
        RoutingPolicy v2 = store.save(v1.withDocument(DOC.replace("\"weight\": 100", "\"weight\": 60")), "test");
        assertEquals(2, v2.version());
        assertThrows(PolicyConflictException.class, () -> store.save(v1, "late"));
        assertEquals(2, store.history("payment", "btcl-wifi-retail", 10).size());

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT data_type FROM information_schema.columns WHERE table_name = 'routing_policy' AND column_name = 'policy_json'")) {
                assertTrue(rs.next());
                assertEquals(columnType, rs.getString(1).toLowerCase(), "the document is a real JSON column");
            }
            // the database itself can look INSIDE the document — what a report or a screen's search will do
            String inside = expected == JdbcPolicyStore.Dialect.POSTGRES
                ? "SELECT policy_json->'rules'->0->'match'->>'app' FROM routing_policy WHERE name = 'btcl-wifi-retail'"
                : "SELECT JSON_UNQUOTE(JSON_EXTRACT(policy_json, '$.rules[0].match.app')) FROM routing_policy WHERE name = 'btcl-wifi-retail'";
            try (ResultSet rs = st.executeQuery(inside)) {
                assertTrue(rs.next());
                assertEquals("wifi-retail", rs.getString(1));
            }
        }

        try (PolicyCatalog catalog = new PolicyCatalog(store, PolicyTypes.defaults()).start(0)) {
            var d = catalog.get("payment", "btcl-wifi-retail").evaluate(
                com.telcobright.seed.routing.api.RoutingRequest.of("payment").with("partner", "btcl").with("app", "wifi-retail").with("zone", "uttara").build(),
                com.telcobright.seed.routing.spi.RouteDirectory.ALL_UP, "policy", false);
            assertEquals("bkash", d.pick().route());
            assertEquals(2, d.policyVersion());
            assertEquals(60, d.pick().weight());
            String before = catalog.snapshot().fingerprint();
            assertTrue(store.delete("payment", "btcl-wifi-retail", "test"));
            assertTrue(catalog.reloadIfChanged());
            assertTrue(!before.equals(catalog.snapshot().fingerprint()));
            assertEquals(3, store.history("payment", "btcl-wifi-retail", 10).size(), "the delete is in the history too");
        }
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS routing_policy_history");
            st.execute("DROP TABLE IF EXISTS routing_policy");
        }
    }

    /** The smallest data source: one connection per call from the URL (credentials inside the URL the caller gave). */
    private record UrlDataSource(String url) implements DataSource {
        @Override public Connection getConnection() throws SQLException { return DriverManager.getConnection(url); }
        @Override public Connection getConnection(String u, String p) throws SQLException { return DriverManager.getConnection(url, u, p); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
