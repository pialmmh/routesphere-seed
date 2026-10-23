package com.telcobright.seed.sessionflow.dependencies;

import com.telcobright.seed.sessionflow.api.IdentificationRule;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.internal.CdrAssembler;
import com.telcobright.seed.sessionflow.internal.ChannelSlots;
import com.telcobright.seed.sessionflow.internal.FlowCounters;
import com.telcobright.seed.sessionflow.internal.RecordRoad;
import com.telcobright.seed.sessionflow.spi.CdrSink;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.SdrSink;
import com.telcobright.seed.sessionflow.spi.SessionJournal;
import com.telcobright.seed.sessionflow.spi.TaskSink;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.spi.TenantResolver;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Everything a {@code RoutesphereFlow} needs from its host, handed in — never looked up (the design's "the only field"): the tenant
 * trees per root (prime-context's snapshots, reloaded per root by the doorbell), the identification rules, the ONE ledger port, the CDR
 * sink, the task sink, the SDR sink, the clock, the zone, the settings — and the base's own shared books (the channel slots, the
 * counters, the CDR sequence). One kit per application; the host builds it once at start.
 */
public final class RoutesphereKit {

    private final TenantLookup tenants;
    private final TenantResolver resolver;
    private final LedgerPort ledger;
    private final CdrSink cdr;
    private final SdrSink sdr;
    private final TaskSink tasks;
    private final RecordRoad records;
    private final SessionJournal journal;
    private final Clock clock;
    private final ZoneId zone;
    private final RoutesphereSettings settings;
    private final ChannelSlots slots = new ChannelSlots();
    private final FlowCounters counters = new FlowCounters();
    private final CdrAssembler cdrAssembler;
    private volatile Function<String, RoutesphereContext> sessions = id -> null;

    private RoutesphereKit(Builder b) {
        this.tenants = required(b.tenants, "tenants (the trees per root)");
        this.ledger = required(b.ledger, "ledger (the one ledger port)");
        this.cdr = required(b.cdr, "cdr (the CDR sink)");
        this.zone = required(b.zone, "zone (the CDR's wall clock)");
        this.sdr = b.sdr;
        this.tasks = b.tasks;
        this.records = b.records == null ? null : new RecordRoad(b.records, b.journal, counters);
        this.journal = b.journal;
        this.clock = b.clock;
        this.settings = b.settings;
        this.resolver = b.rules != null ? checkedResolver(b.rules, tenants) : b.resolver;
        this.cdrAssembler = new CdrAssembler(zone, clock.millis() * 1000);
    }

    public static Builder builder() { return new Builder(); }

    /** The tenant trees per root (§D): one immutable snapshot per root, a session keeps the one it started with. */
    public TenantLookup tenants() { return tenants; }

    /** The identification rules (§C): an ingress → the ROOT tenant's code; no match = nobody's, never a default. */
    public TenantResolver resolver() { return resolver; }

    /** The ONE money road (§E): reserve, settle, release — idempotent per (session, tier). */
    public LedgerPort ledger() { return ledger; }

    /** Where the CDR goes: one message per session, one record per tier, leaf first, on {@code cdr_<root>}. */
    public CdrSink cdr() { return cdr; }

    /** Where the session record goes. */
    public SdrSink sdr() { return sdr; }

    /** Where the closed task row goes. */
    public TaskSink tasks() { return tasks; }

    /** The records road (P1-R): one writer per root store; null = the lane's CDR goes to its CDR sink. */
    public RecordRoad records() { return records; }

    /** The records road's stores, one per root; null = the lane's CDR goes to its CDR sink. */
    public RecordStores recordStores() { return records == null ? null : records.stores(); }

    /** The sessions in the air (R1-6): none unless the host keeps one. */
    public SessionJournal journal() { return journal; }

    public Clock clock() { return clock; }

    /** The root tenant's zone: the CDR's times are its wall clock. */
    public ZoneId zone() { return zone; }

    public RoutesphereSettings settings() { return settings; }

    /** The partners' concurrent channels (the call's slot at the leaf), shared by every session of the application. */
    public ChannelSlots slots() { return slots; }

    /** The application's running numbers. */
    public FlowCounters counters() { return counters; }

    /** The CDR builder (its running sequence is the producer's own). */
    public CdrAssembler cdrAssembler() { return cdrAssembler; }

    /** A live session's context by its id — how a helper child restored from the store finds its session again (the engine attaches it). */
    public Optional<RoutesphereContext> sessionOf(String sessionId) { return Optional.ofNullable(sessions.apply(sessionId)); }

    /** The engine attaches its registry's lookup once it is built. */
    public void attachSessions(Function<String, RoutesphereContext> lookup) { this.sessions = required(lookup, "the sessions' lookup"); }

    /** Every rule must name a root this process serves: a rule naming an unknown root refuses the start (§C). */
    private static TenantResolver checkedResolver(List<IdentificationRule> rules, TenantLookup tenants) {
        for (IdentificationRule rule : rules) {
            boolean served = tenants.rootOfCode(rule.tenant()).isPresent() || tenants.root(rule.tenant()).isPresent();
            if (!served) throw new IllegalStateException("the identification rule " + rule + " names the root '" + rule.tenant()
                + "', which this process does not serve: refused at load — a session it matched would have no tree");
        }
        return TenantResolvers.ofRules(rules);
    }

    public static final class Builder {
        private TenantLookup tenants;
        private TenantResolver resolver = TenantResolvers.none();
        private List<IdentificationRule> rules;
        private LedgerPort ledger;
        private CdrSink cdr;
        private SdrSink sdr = SdrSink.NONE;
        private TaskSink tasks = TaskSink.NONE;
        private RecordStores records;
        private SessionJournal journal = SessionJournal.NONE;
        private Clock clock = Clock.systemUTC();
        private ZoneId zone;
        private RoutesphereSettings settings = RoutesphereSettings.defaults();

        private Builder() {}

        public Builder tenants(TenantLookup v) { this.tenants = v; return this; }
        /** The identification rules of the bootstrap; each must name a served root (checked at {@link #build}). */
        public Builder identification(List<IdentificationRule> v) { this.rules = List.copyOf(required(v, "identification")); return this; }
        /** A resolver of the product's own (no rule check). */
        public Builder resolver(TenantResolver v) { this.resolver = required(v, "resolver"); return this; }
        public Builder ledger(LedgerPort v) { this.ledger = v; return this; }
        public Builder cdr(CdrSink v) { this.cdr = v; return this; }
        public Builder sdr(SdrSink v) { this.sdr = required(v, "sdr"); return this; }
        public Builder tasks(TaskSink v) { this.tasks = required(v, "tasks"); return this; }
        /** The records road (P1-R): the base writes the lane's CDR, SDR and summary input into each root's own store. */
        public Builder records(RecordStores v) { this.records = required(v, "records"); return this; }
        public Builder journal(SessionJournal v) { this.journal = required(v, "journal"); return this; }
        public Builder clock(Clock v) { this.clock = required(v, "clock"); return this; }
        public Builder zone(ZoneId v) { this.zone = v; return this; }
        public Builder settings(RoutesphereSettings v) { this.settings = required(v, "settings"); return this; }

        /** Refuses to build without the trees, the ledger, the CDR sink or the zone: there is no fallback for them. */
        public RoutesphereKit build() { return new RoutesphereKit(this); }
    }

    private static <T> T required(T value, String name) {
        if (value == null) throw new IllegalStateException("the routesphere kit needs " + name + " — there is no default for it");
        return value;
    }
}
