package com.telcobright.seed.sessionflow.dependencies;

import com.telcobright.seed.sessionflow.spi.CdrSink;
import com.telcobright.seed.sessionflow.spi.SummaryPing;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The records road a host hands its kit (P1-R, the owner's ruling of 2026-10-23): the application makes its CDR and SDR SQL, the base writes
 * it — the records and the summary input ({@code summary_affected}) of a batch of sessions in ONE transaction — into each root's own store.
 * One store per root; a lane on this road has no CDR sink ({@code RoutesphereKit} refuses both at once). The knobs come from the product's
 * profile; nothing here is read from the environment.
 */
public final class RecordStores {

    /** The defaults: a batch of 200 tier records or 250 ms; a failed batch tried again 1 s, 2 s, … 30 s apart, parked after 7 tries (~1 min). */
    public static final int BATCH_RECORDS = 200, RETRY_BUDGET = 7;
    public static final Duration BATCH_WITHIN = Duration.ofMillis(250), RETRY_FIRST = Duration.ofSeconds(1), RETRY_CAP = Duration.ofSeconds(30);

    private final Map<String, RecordStore> byRoot;
    private final int batchRecords;
    private final Duration batchWithin, retryFirst, retryCap;
    private final int retryBudget;
    private final SummaryPing ping;
    private final CdrSink parkInto;

    private RecordStores(Builder b) {
        this.byRoot = Map.copyOf(b.byRoot);
        this.batchRecords = b.batchRecords;
        this.batchWithin = b.batchWithin;
        this.retryFirst = b.retryFirst;
        this.retryCap = b.retryCap;
        this.retryBudget = b.retryBudget;
        this.ping = b.ping;
        this.parkInto = b.parkInto;
    }

    public static Builder builder() { return new Builder(); }

    /** The store of a root, by its database name. Empty = this road has no store for it. */
    public Optional<RecordStore> of(String root) { return root == null ? Optional.empty() : Optional.ofNullable(byRoot.get(root)); }

    public Collection<RecordStore> all() { return byRoot.values(); }

    /** A batch closes at this many tier records… */
    public int batchRecords() { return batchRecords; }

    /** …or this long after its first, whichever comes first. */
    public Duration batchWithin() { return batchWithin; }

    /** A failed batch is tried again after this, then twice as long each time… */
    public Duration retryFirst() { return retryFirst; }

    /** …up to this. */
    public Duration retryCap() { return retryCap; }

    /** The tries a failing batch gets before its writer PARKS (said once; it goes on trying at the cap). */
    public int retryBudget() { return retryBudget; }

    /** The summary ping after each commit; {@link SummaryPing#NONE} = summary-service polls. */
    public SummaryPing ping() { return ping; }

    /**
     * Where a parked writer keeps the records the journal of the sessions in the air does not hold (the file CDR journal, as today:
     * {@code CdrSinks.journal}); null = in this process only.
     */
    public CdrSink parkInto() { return parkInto; }

    public static final class Builder {
        private final Map<String, RecordStore> byRoot = new LinkedHashMap<>();
        private int batchRecords = BATCH_RECORDS;
        private Duration batchWithin = BATCH_WITHIN, retryFirst = RETRY_FIRST, retryCap = RETRY_CAP;
        private int retryBudget = RETRY_BUDGET;
        private SummaryPing ping = SummaryPing.NONE;
        private CdrSink parkInto;

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

        public Builder batch(int records, Duration within) {
            if (records <= 0 || within == null || within.isNegative() || within.isZero()) throw new IllegalArgumentException("a batch needs records > 0 and a time > 0");
            this.batchRecords = records;
            this.batchWithin = within;
            return this;
        }

        public Builder retries(Duration first, Duration cap) { return retries(first, cap, retryBudget); }

        /** The waits between the tries of a failing batch, and the tries before its writer parks. */
        public Builder retries(Duration first, Duration cap, int budget) {
            if (first == null || cap == null || first.isNegative() || first.isZero() || cap.compareTo(first) < 0 || budget <= 0) {
                throw new IllegalArgumentException("a retry waits more than nothing, the cap is not shorter than the first wait, and the budget is one try or more");
            }
            this.retryFirst = first;
            this.retryCap = cap;
            this.retryBudget = budget;
            return this;
        }

        /** The file CDR journal for the records a parked writer holds that the journal of the sessions in the air does not. */
        public Builder parkInto(CdrSink v) { this.parkInto = Objects.requireNonNull(v, "parkInto"); return this; }

        public Builder ping(SummaryPing v) { this.ping = Objects.requireNonNull(v, "ping"); return this; }

        /** Refuses to build without a store: a road with nowhere to write is not a road. */
        public RecordStores build() {
            if (byRoot.isEmpty()) throw new IllegalStateException("the records road needs a store for at least one root");
            return new RecordStores(this);
        }
    }
}
