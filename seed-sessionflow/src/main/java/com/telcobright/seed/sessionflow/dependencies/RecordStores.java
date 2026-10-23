package com.telcobright.seed.sessionflow.dependencies;

import javax.sql.DataSource;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The records road a host hands its kit (P1-R, the owner's ruling of 2026-10-23): the application makes its CDR and SDR SQL, the base writes
 * it — the records and the summary input ({@code summary_affected}) of a batch of sessions in ONE transaction — into each root's own store.
 * One store per root; a lane on this road has no CDR sink ({@code RoutesphereKit} refuses both at once).
 */
public final class RecordStores {

    private final Map<String, RecordStore> byRoot;

    private RecordStores(Builder b) {
        this.byRoot = Map.copyOf(b.byRoot);
    }

    public static Builder builder() { return new Builder(); }

    /** The store of a root, by its database name. Empty = this road has no store for it. */
    public Optional<RecordStore> of(String root) { return root == null ? Optional.empty() : Optional.ofNullable(byRoot.get(root)); }

    public Collection<RecordStore> all() { return byRoot.values(); }

    public static final class Builder {
        private final Map<String, RecordStore> byRoot = new LinkedHashMap<>();

        private Builder() {}

        /** A root whose tiers are schemas of one PostgreSQL database (the ad lane, ad-is-a-call §3). */
        public Builder postgres(String root, DataSource dataSource) { return store(new RecordStore(root, dataSource, RecordDialect.POSTGRESQL, 0)); }

        /** A root whose tiers are databases of one MySQL server (the call lane). */
        public Builder mysql(String root, DataSource dataSource) { return store(new RecordStore(root, dataSource, RecordDialect.MYSQL, 0)); }

        /** A store as given (its switch id included). Two stores for one root are refused. */
        public Builder store(RecordStore store) {
            if (byRoot.putIfAbsent(store.root(), store) != null) {
                throw new IllegalStateException("two records stores for the root '" + store.root() + "': a session could not say which one is its own");
            }
            return this;
        }

        /** Refuses to build without a store: a road with nowhere to write is not a road. */
        public RecordStores build() {
            if (byRoot.isEmpty()) throw new IllegalStateException("the records road needs a store for at least one root");
            return new RecordStores(this);
        }
    }
}
