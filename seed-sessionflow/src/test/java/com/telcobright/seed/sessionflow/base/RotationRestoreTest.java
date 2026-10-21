package com.telcobright.seed.sessionflow.base;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.Candidate;
import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.api.TierRate;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.sessionflow.testkit.ManualClock;
import com.telcobright.seed.sessionflow.testkit.RecordingCdrSink;
import com.telcobright.statewalk.persistence.SnapshotSerializer;
import com.telcobright.statewalk.session.Outcome;
import com.telcobright.statewalk.session.events.Established;
import com.telcobright.statewalk.session.events.SessionStart;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-F2 · The closed spans survive a restore (O4 + §L.6): a session that rotated its tier's account is restored from its row and rotates
 * again — every reserve reference stays unique (the next span is {@code <sid>.3}, never a repeat of {@code <sid>.2}), every span keeps its
 * record in the CDR, the live span's seconds are only its own, and money moves once per span. Driven through the handler on a manual
 * clock: the times exact, the renewals by hand.
 */
class RotationRestoreTest {

    private static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    private final ManualClock clock = new ManualClock(Instant.parse("2026-10-22T00:00:00Z"), DHAKA);
    private final InMemoryLedger ledger = new InMemoryLedger()
        .fund("btcl", 1, "2.00")                                   // the first purchase: the admission's unit and one window
        .fundAccount("btcl", 1, 77, "1.00")                         // the second purchase: one window
        .fundAccount("btcl", 1, 88, "10.00");                       // the third
    private final RecordingCdrSink cdrs = new RecordingCdrSink();

    private LabFlow flow() {
        RoutesphereKit kit = RoutesphereKit.builder().tenants(TenantLookup.of(Lab.btclTree())).identification(List.of(Lab.RULES.get(0)))
            .ledger(ledger).cdr(cdrs).zone(DHAKA).clock(clock).settings(Lab.settings(60)).build();
        return new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of()) {
            @Override protected TierRate rateNextWindow(LabContext ctx, LevelAdmission tier) { return TierRate.of(BigDecimal.ONE, BigDecimal.ONE, "BDT", "880"); }
            @Override protected Long nextAccount(LabContext ctx, LevelAdmission tier) {
                Long now = tier.getChargeAccountId();
                if (Long.valueOf(88).equals(now)) return null;
                return Long.valueOf(77).equals(now) ? 88L : 77L;
            }
        };
    }

    @Test
    void rotate_restore_rotateAgain_end_everyReferenceUnique_everySpanRecorded_theLiveSecondsItsOwn_moneyOncePerSpan() {
        LabFlow before = flow();
        LabContext ctx = new LabContext();
        ctx.facts = new LabFacts("rr", clock.millis(), Lab.BTCL_DOOR, 1, "8801");
        before.onStart(ctx, new SessionStart(ctx.facts));
        ctx.candidates = List.of(Candidate.SELF);
        assertThat(before.admit(ctx).accepted()).isTrue();                            // rr#L0: 1.00 on the first purchase
        long answeredAt = clock.millis();
        ctx.answer = new Established(answeredAt, "carrier-a", null, null);
        ctx.activeAtMs = answeredAt;

        clock.advance(60_000);
        assertThat(before.renewReserves(ctx)).isEqualTo(60.0);                        // rr#L0#W2: the first purchase spent
        clock.advance(60_000);
        assertThat(before.renewReserves(ctx)).isEqualTo(60.0);                        // refused → span 1 closed at 120 s; rr.2#L0 on 77
        String row = SnapshotSerializer.contextToBase64Json(ctx);                    // the last row that landed

        LabFlow after = flow();                                                       // the next node
        LabContext restored = (LabContext) SnapshotSerializer.contextFromBase64Json(row, LabContext.class.getName());
        clock.advance(60_000);
        assertThat(after.renewReserves(restored)).isEqualTo(60.0);                    // 77 spent → span 2 closed at 180 s; rr.3#L0 on 88
        clock.advance(30_000);
        restored.endedAtMs = clock.millis();
        after.settle(restored, Outcome.SUCCEEDED);
        after.record(restored, Outcome.SUCCEEDED);

        List<String> reserves = ledger.journal().stream().filter(e -> e.verb().equals("reserve")).map(e -> e.reference()).toList();
        assertThat(reserves).containsExactly("rr#L0", "rr#L0#W2", "rr.2#L0", "rr.3#L0");
        assertThat(ledger.journal().stream().filter(e -> e.verb().equals("settle")).map(e -> e.reference()))
            .as("money once per span").containsExactly("rr#L0", "rr.2#L0", "rr.3#L0");
        assertThat(ledger.balanceOf("btcl", 1)).isEqualByComparingTo("0.00");
        assertThat(ledger.balanceOfAccount("btcl", 1, 77)).isEqualByComparingTo("0.00");
        assertThat(ledger.balanceOfAccount("btcl", 1, 88)).isEqualByComparingTo("9.00");
        assertThat(ledger.openReserves()).isZero();
        assertThat(restored.spanNo()).isEqualTo(3);
        assertThat(restored.durationSec).isEqualTo(210.0);
        assertThat(restored.spanSeconds()).as("the live span: 180 s → 210 s").isEqualTo(30.0);
        List<CdrEvent> records = cdrs.of("rr").get(0).tiers();
        assertThat(records).extracting(r -> r.channelCallUuid + ":" + r.inPartnerCost.stripTrailingZeros().toPlainString())
            .containsExactly("rr.1:2", "rr.2:1", "rr.3:1");
        assertThat(records).extracting(r -> r.durationSec.stripTrailingZeros().toPlainString()).containsExactly("120", "60", "30");
    }
}
