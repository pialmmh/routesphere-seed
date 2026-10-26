package com.telcobright.seed.prepaid.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.LaneAnswers;
import com.telcobright.seed.sessionflow.api.LogText;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.spi.SessionJournal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R1-6 · The money of the sessions a stopped process left in the air, at the next start — MOVED as it was from the base's
 * {@code SessionCdr} (L2a 2a, design §11; the records themselves stay the base's): a session handed over is closed through the ledger as
 * settled at exactly what it reserved (A2); one never handed over has every reserve given back (F9). A ledger that refuses or does not
 * answer = OWED, one ERROR naming the reference. Stateless: one per ask.
 */
final class LeftoverMoney {

    /** The base's own log name: the lines read exactly as before the move. */
    private static final Logger log = LoggerFactory.getLogger(RoutesphereFlow.class);

    private final LaneAnswers<?> flow;

    LeftoverMoney(LaneAnswers<?> flow) { this.flow = flow; }

    /**
     * A2 (ARCH-0077): a call HANDED OVER when the process died is charged what it reserved (the record above) — so each tier's reserve is
     * CLOSED through the ledger as settled at exactly its reserved sum: the switch ledger deletes the tier's reserve row and moves nothing
     * (reserved − charged = 0), and the orphan reaper can never give back money a record has charged. One tier = one settle, under the
     * tier's FIRST reference (the row's key; a renewal's {@code #W<n>} grew the same row), charged the sum of every window the journal holds.
     * A ledger that refuses or does not answer = OWED: one ERROR naming the reference. Answers {closed, owed}.
     */
    int[] closeAsReserved(SessionJournal.Leftover left) {
        int closed = 0, owed = 0;
        Map<Integer, List<SessionJournal.Held>> byTier = new LinkedHashMap<>();
        for (SessionJournal.Held held : left.reserves()) byTier.computeIfAbsent(held.tier(), t -> new ArrayList<>()).add(held);
        for (List<SessionJournal.Held> windows : byTier.values()) {
            SessionJournal.Held first = windows.stream().filter(h -> h.reference() != null && !h.reference().contains("#W")).findFirst().orElse(windows.get(0));
            BigDecimal reserved = windows.stream().map(h -> h.amount() == null ? BigDecimal.ZERO : h.amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
            LevelAdmission level = levelOf(first);
            level.addToTotalReserved(reserved);
            try {
                flow.kit().ledger().settle(level, reserved);
                closed++;
            } catch (RuntimeException e) {
                owed++;
                flow.kit().counters().owed.incrementAndGet();
                log.error("[{}] {} | OWED: the reserve {} ({} {}) of tier {} ({}) partner {} of a call left in the air was NOT closed as reserved: {}",
                    flow.name(), LogText.clean(left.callId()), LogText.clean(first.reference()), reserved, first.uom(), first.tier(), first.tenant(), first.partnerId(), LogText.failureText(e));
            }
        }
        return new int[] {closed, owed};
    }

    /**
     * F9: one reserve of a call that was never handed over, given back through the kit's ledger by its reference — the tier found again
     * in the call's own tree (the lookup by root and database name), else named by its database name alone; the partner the tier's own
     * row, else a shell with the id. False = the ledger refused or did not answer: OWED, one ERROR naming the reference.
     */
    boolean giveBack(String callId, SessionJournal.Held held) {
        try {
            flow.kit().ledger().release(levelOf(held), SessionCause.LOST_AT_RESTART);
            return true;
        } catch (RuntimeException e) {
            flow.kit().counters().owed.incrementAndGet();
            log.error("[{}] {} | OWED: the reserve {} of {} {} at tier {} ({}) partner {} of a call a stopped process left before its hand-over was NOT given back: {}",
                flow.name(), LogText.clean(callId), LogText.clean(held.reference()), held.amount(), held.uom(), held.tier(), held.tenant(), held.partnerId(), LogText.failureText(e));
            return false;
        }
    }

    private LevelAdmission levelOf(SessionJournal.Held held) {
        Tenant tier = flow.kit().tenants().tenantByDbName(held.root() == null ? held.tenant() : held.root(), held.tenant()).orElse(null);
        if (tier == null) tier = new Tenant(held.tenant());
        Partner partner = tier.getContext() == null || tier.getContext().getPartners() == null ? null : tier.getContext().getPartners().get(held.partnerId());
        if (partner == null) { partner = new Partner(); partner.setIdPartner(held.partnerId()); }
        LevelAdmission level = new LevelAdmission(held.tier(), tier, partner, null);
        level.setDebitReference(held.reference());
        level.setReservedAmount(held.amount() == null ? BigDecimal.ZERO : held.amount());
        level.setChargeAccountId(held.account());
        if (held.uom() != null) level.setUom(held.uom());
        return level;
    }
}
