package com.telcobright.seed.callflow.dependencies;

import com.telcobright.seed.callflow.spi.CallJournal;
import com.telcobright.seed.callflow.spi.CdrSink;
import com.telcobright.seed.callflow.spi.LedgerPort;
import com.telcobright.seed.callflow.spi.TenantLookup;
import com.telcobright.statewalk.session.SdrSink;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Everything a {@code CallFlow} needs from its host, handed in — never looked up: the tenant tree, the ledger, the CDR
 * sink, the clock and the settings. One kit per application; the host builds it once at start.
 */
public final class CallFlowKit {

    private final TenantLookup tenants;
    private final LedgerPort ledger;
    private final CdrSink cdrSink;
    private final SdrSink sdrSink;
    private final CallJournal journal;
    private final Clock clock;
    private final ZoneId zone;
    private final CallFlowSettings settings;

    private CallFlowKit(Builder b) {
        this.tenants = required(b.tenants, "tenants");
        this.ledger = required(b.ledger, "ledger");
        this.cdrSink = required(b.cdrSink, "cdrSink");
        this.zone = required(b.zone, "zone");
        this.sdrSink = b.sdrSink;
        this.journal = b.journal;
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
    public CallJournal journal() { return journal; }

    public Clock clock() { return clock; }

    /** The root tenant's zone: the CDR's times are its wall clock. */
    public ZoneId zone() { return zone; }

    public CallFlowSettings settings() { return settings; }

    public static final class Builder {
        private TenantLookup tenants;
        private LedgerPort ledger;
        private CdrSink cdrSink;
        private SdrSink sdrSink = record -> { };
        private CallJournal journal = CallJournal.NONE;
        private Clock clock = Clock.systemUTC();
        private ZoneId zone;
        private CallFlowSettings settings = CallFlowSettings.defaults();

        private Builder() {}

        public Builder tenants(TenantLookup v) { this.tenants = v; return this; }
        public Builder ledger(LedgerPort v) { this.ledger = v; return this; }
        public Builder cdrSink(CdrSink v) { this.cdrSink = v; return this; }
        public Builder sdrSink(SdrSink v) { this.sdrSink = required(v, "sdrSink"); return this; }
        public Builder journal(CallJournal v) { this.journal = required(v, "journal"); return this; }
        public Builder clock(Clock v) { this.clock = required(v, "clock"); return this; }
        public Builder zone(ZoneId v) { this.zone = v; return this; }
        public Builder settings(CallFlowSettings v) { this.settings = required(v, "settings"); return this; }

        /** Refuses to build without the tenant tree, the ledger, the CDR sink or the zone: there is no fallback for them. */
        public CallFlowKit build() { return new CallFlowKit(this); }
    }

    private static <T> T required(T value, String name) {
        if (value == null) throw new IllegalStateException("the call flow kit needs " + name + " — there is no default for it");
        return value;
    }
}
