package com.telcobright.seed.ledgerclient.dependencies;

import com.telcobright.seed.ledgerclient.api.RemoteLedger;
import com.telcobright.seed.ledgerclient.internal.HttpTransport;
import com.telcobright.seed.ledgerclient.spi.Transport;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * C2 · The client of ONE root's cached ledger, kept by the application that runs its sessions: where the application mounts its ledger
 * roads, which root, the service token (asked at every request — from the caller's secret store, never a literal), how long one request
 * may take.
 *
 * <pre>
 *   RemoteLedger ledger = RemoteLedgers.builder()
 *       .mount("http://10.10.199.30:7150/ledger/").root("btcl")
 *       .token(secrets::ledgerToken).timeout(Duration.ofSeconds(5))
 *       .build();
 * </pre>
 */
public final class RemoteLedgers {

    private RemoteLedgers() {}

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private URI mount;
        private String root;
        private Supplier<String> token;
        private Duration timeout = Duration.ofSeconds(5);
        private Transport transport;

        private Builder() {}

        /** The application's ledger mount: every road is below it ({@code <root>/units}, {@code <root>/rows/…}). */
        public Builder mount(String url) {
            if (url == null || url.isBlank()) throw new IllegalArgumentException("the ledger's mount is a URL: there is no default");
            String u = url.trim();
            this.mount = URI.create(u.endsWith("/") ? u : u + "/");
            return this;
        }

        /** The root whose cached ledger this is (its database name). */
        public Builder root(String root) {
            if (root == null || root.isBlank()) throw new IllegalArgumentException("a root is named");
            this.root = root.trim();
            return this;
        }

        /** The service token, asked at every request (a rotated token is taken at once). */
        public Builder token(Supplier<String> token) { this.token = Objects.requireNonNull(token, "token"); return this; }

        /** How long one request may take (default 5 s); longer is a {@code RemoteLedgerFault} — send the unit again under its reference. */
        public Builder timeout(Duration timeout) {
            if (timeout == null || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("the timeout is positive");
            this.timeout = timeout;
            return this;
        }

        /** Another way to the application than HTTP (a test's: the application's roads in the same JVM). */
        public Builder transport(Transport transport) { this.transport = Objects.requireNonNull(transport, "transport"); return this; }

        public RemoteLedger build() {
            if (root == null) throw new IllegalStateException("the client names its root");
            if (transport == null && mount == null) throw new IllegalStateException("the client names the application's ledger mount (or a transport)");
            return new RemoteLedger(transport != null ? transport : new HttpTransport(mount, timeout), root, token);
        }
    }
}
