package com.telcobright.seed.sessionflow.dependencies;

import com.telcobright.seed.sessionflow.spi.SessionJournal;
import com.telcobright.seed.sessionflow.spi.TaskSink;
import com.telcobright.seed.sessionflow.spi.CdrSink;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.statewalk.session.SdrSink;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Everything a {@code SessionFlow} needs from its host, handed in — never looked up: the tenant tree, the ledger, the CDR
 * sink, the clock and the settings. One kit per application; the host builds it once at start.
 */
public final class SessionFlowKit {

    private final TenantLookup tenants;
    private final LedgerPort ledger;
    private final CdrSink cdrSink;
    private final SdrSink sdrSink;
    private final SessionJournal journal;
    private final TaskSink tasks;
    private final Clock clock;
    private final ZoneId zone;
    private final SessionFlowSettings settings;

    private SessionFlowKit(Builder b) {
        this.tenants = required(b.tenants, "tenants");
        this.ledger = required(b.ledger, "ledger");
        this.cdrSink = required(b.cdrSink, "cdrSink");
        this.zone = required(b.zone, "zone");
        this.sdrSink = b.sdrSink;
        this.journal = b.journal;
        this.tasks = b.tasks;
        this.clock = b.clock;
        this.settings = b.settings;
    }

    public static Builder builder() { return new Builder(); }

    /** The tenant tree as the config service serves it. */
    public TenantLookup tenants() { return tenants; }

    public LedgerPort ledger() { return ledger; }

    public CdrSink cdrSink() { return cdrSink; }

    /** Where the session record (the call's timeline) goes. */
    public SdrSink sdrSink() { return sdrSink; }

    /** The calls in the air (R1-6): none unless the host keeps one — then a process death loses no handed-over call's record. */
    public SessionJournal journal() { return journal; }
    /** Where a closed task row goes (the owner 2026-10-09: every session kind carries a CampaignTask): none unless the host keeps a store. */
    public TaskSink tasks() { return tasks; }

    public Clock clock() { return clock; }

    /** The root tenant's zone: the CDR's times are its wall clock. */
    public ZoneId zone() { return zone; }

    public SessionFlowSettings settings() { return settings; }

    public static final class Builder {
        private TenantLookup tenants;
        private LedgerPort ledger;
        private CdrSink cdrSink;
        private SdrSink sdrSink = record -> { };
        private SessionJournal journal = SessionJournal.NONE;
        private TaskSink tasks = TaskSink.NONE;
        private Clock clock = Clock.systemUTC();
        private ZoneId zone;
        private SessionFlowSettings settings = SessionFlowSettings.defaults();

        private Builder() {}

        public Builder tenants(TenantLookup v) { this.tenants = v; return this; }
        public Builder ledger(LedgerPort v) { this.ledger = v; return this; }
        public Builder cdrSink(CdrSink v) { this.cdrSink = v; return this; }
        public Builder sdrSink(SdrSink v) { this.sdrSink = required(v, "sdrSink"); return this; }
        public Builder journal(SessionJournal v) { this.journal = required(v, "journal"); return this; }
        public Builder tasks(TaskSink v) { this.tasks = required(v, "tasks"); return this; }
        public Builder clock(Clock v) { this.clock = required(v, "clock"); return this; }
        public Builder zone(ZoneId v) { this.zone = v; return this; }
        public Builder settings(SessionFlowSettings v) { this.settings = required(v, "settings"); return this; }

        /** Refuses to build without the tenant tree, the ledger, the CDR sink or the zone: there is no fallback for them. */
        public SessionFlowKit build() { return new SessionFlowKit(this); }
    }

    private static <T> T required(T value, String name) {
        if (value == null) throw new IllegalStateException("the call flow kit needs " + name + " — there is no default for it");
        return value;
    }
}
