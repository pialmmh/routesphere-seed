package com.telcobright.seed.sessionflow.dependencies;

import com.telcobright.seed.sessionflow.internal.OrchestrixLedgerMaker;
import com.telcobright.seed.sessionflow.internal.RealKafkaClients;
import com.telcobright.seed.sessionflow.spi.KafkaClients;
import com.telcobright.seed.sessionflow.spi.LedgerMaker;
import com.telcobright.seed.sessionflow.spi.SessionJournal;
import com.telcobright.seed.sessionflow.spi.TreesMaker;

import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.function.Function;

/**
 * What a kit made from the bootstrap is built WITH: the makers of the roots' trees and ledgers, the Kafka clients, the journal of a root's
 * directory, the clock and the environment. {@link #onTheClasspath()} is the deployed set — the trees and the ledgers found on the
 * classpath ({@code java.util.ServiceLoader}: seed-tenant's prime-context trees, seed-switch-ledger's {@code switch} ledger; the
 * {@code orchestrix} ledger is built in), the real Kafka clients, a file journal per root; a test builds its own with fakes.
 */
public final class KitMakers {

    /** The file of a root's sessions in the air, in the root's own directory. */
    public static final String JOURNAL_FILE = "sessions-in-the-air.jsonl";

    private final TreesMaker trees;
    private final Map<String, LedgerMaker> ledgers;
    private final KafkaClients kafka;
    private final Function<Path, SessionJournal> journals;
    private final Clock clock;
    private final Function<String, String> environment;

    private KitMakers(Builder b) {
        this.trees = b.trees;
        this.ledgers = Map.copyOf(b.ledgers);
        this.kafka = Objects.requireNonNull(b.kafka, "kafka");
        this.journals = Objects.requireNonNull(b.journals, "journals");
        this.clock = Objects.requireNonNull(b.clock, "clock");
        this.environment = Objects.requireNonNull(b.environment, "environment");
    }

    /** The deployed makers: what the classpath carries, the real Kafka, a file journal per root, the system clock and environment. */
    public static KitMakers onTheClasspath() {
        Builder b = builder();
        ServiceLoader.load(TreesMaker.class).findFirst().ifPresent(b::trees);
        ServiceLoader.load(LedgerMaker.class).forEach(b::ledger);
        return b.build();
    }

    public static Builder builder() { return new Builder(); }

    /** The trees' maker; refused in words when the classpath has none. */
    public TreesMaker trees() {
        if (trees == null) throw new IllegalStateException("no maker of the roots' trees on the classpath — add seed-tenant (prime-context's trees and the doorbell)");
        return trees;
    }

    /** The maker of a ledger kind; refused in words when the classpath has none. */
    public LedgerMaker ledger(String kind) {
        LedgerMaker maker = ledgers.get(kind);
        if (maker == null) throw new IllegalStateException("the ledger kind '" + kind + "' has no maker on the classpath (known: " + ledgers.keySet()
            + ")" + ("switch".equals(kind) ? " — add seed-switch-ledger (the MemLedger port, R12)" : ""));
        return maker;
    }

    public KafkaClients kafka() { return kafka; }

    /** The journal of the sessions in the air of a root's directory. */
    public SessionJournal journalOf(Path rootDir) { return journals.apply(rootDir); }

    public Clock clock() { return clock; }

    public Function<String, String> environment() { return environment; }

    public static final class Builder {
        private TreesMaker trees;
        private final Map<String, LedgerMaker> ledgers = new LinkedHashMap<>();
        private KafkaClients kafka = new RealKafkaClients();
        private Function<Path, SessionJournal> journals = dir -> SessionJournals.file(dir.resolve(JOURNAL_FILE));
        private Clock clock = Clock.systemUTC();
        private Function<String, String> environment = System::getenv;

        private Builder() { ledger(new OrchestrixLedgerMaker()); }

        public Builder trees(TreesMaker v) { this.trees = v; return this; }
        /** A ledger maker, by its kind (a later one of the same kind replaces the earlier). */
        public Builder ledger(LedgerMaker v) { ledgers.put(v.kind(), v); return this; }
        public Builder kafka(KafkaClients v) { this.kafka = v; return this; }
        public Builder journals(Function<Path, SessionJournal> v) { this.journals = v; return this; }
        public Builder clock(Clock v) { this.clock = v; return this; }
        public Builder environment(Function<String, String> v) { this.environment = v; return this; }
        public KitMakers build() { return new KitMakers(this); }
    }
}
