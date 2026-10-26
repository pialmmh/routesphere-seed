package com.telcobright.seed.prepaid.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.ClosedSpan;
import com.telcobright.seed.sessionflow.api.LaneAnswers;
import com.telcobright.seed.sessionflow.api.LogText;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.api.TierRate;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * O4 · The rotation of a tier's account at a renewal (C14; the WiFi's B17): the ledger refused the next window on the current
 * account, the application names the next one, and the base moves the tier onto it — the current series settled for everything it
 * held and closed as a {@link ClosedSpan}, a fresh series opened on the next account with its first window held at once. The tier
 * keeps its place in the chain; the tiers above are untouched. A next account that cannot fund its first window is the cut.
 *
 * <p>L2a 2a: MOVED as it was from the base ({@code seed-sessionflow api/SpanRotation}, design §11); the lane's answers through
 * {@link LaneAnswers}.
 */
final class SpanRotation<C extends RoutesphereContext> {

    /** The base's own log name: the lines read exactly as before the move. */
    private static final Logger log = LoggerFactory.getLogger(RoutesphereFlow.class);

    private final LaneAnswers<C> flow;

    private SpanRotation(LaneAnswers<C> flow) { this.flow = flow; }

    static <C extends RoutesphereContext> SpanRotation<C> of(LaneAnswers<C> flow) { return new SpanRotation<>(flow); }

    /** @return true = the session goes on, on the next account; false = the money ended (the cut) */
    boolean rotateOrCut(C ctx, LevelAdmission level, TierRate window) {
        Long next = flow.safely(ctx, "nextAccount", () -> flow.nextAccount(ctx, level), null);
        if (next == null) return false;
        if (!closeSpan(ctx, level)) return false;
        LevelAdmission fresh = freshLevel(level, next);
        replace(ctx, level, fresh);
        ctx.keepTiers();                                                               // the closed span is in the session's row at once
        String reference = ctx.sessionId() + "." + ctx.spanNo() + "#L" + level.getLevelIndex();
        String refusal = TierReserve.of(flow).reserveForRenewal(ctx, fresh, window.reserveAmount(), reference);
        return firstWindowHeld(ctx, fresh, next, refusal);
    }

    /** The current series is settled for everything it held — every window held was used — and recorded as a closed span. */
    private boolean closeSpan(C ctx, LevelAdmission level) {
        long now = flow.kit().clock().millis();
        long startedAt = ctx.spanStartedAtMs > 0 ? ctx.spanStartedAtMs : ctx.activeAtMs;
        TierSettlement done;
        try {
            done = MoneyUnit.moves(flow.kit().ledger()).settle(level, TierSettlement.reservedOf(level));
        } catch (RuntimeException e) {
            ctx.history.note(flow.name(), "tier " + level.getLevelIndex() + ": the ledger did not take the settlement of " + level.getDebitReference()
                + " at the rotation (" + e + ") — the cut; the end settles it");
            return false;
        }
        MoneyUnit.afterTheRecord(() -> journalSettled(ctx, level));
        List<ClosedSpan> spans = new ArrayList<>(ctx.closedSpans);
        spans.add(new ClosedSpan(ctx.spanNo(), level, done, startedAt, now));
        ctx.closedSpans = List.copyOf(spans);
        ctx.spanStartedAtMs = now;
        ctx.history.note(flow.name(), "tier " + level.getLevelIndex() + " (" + level.getDbName() + "): account " + accountOf(level) + " could fund no more — settled at "
            + done.charged() + " under " + level.getDebitReference() + " (span " + spans.size() + ")");
        return true;
    }

    /** The settled series' reserves are no longer in the air: the next start leaves them (F9). */
    private void journalSettled(C ctx, LevelAdmission level) {
        for (int w = 1; w <= level.getReservationCount(); w++) {
            String reference = w == 1 ? level.getDebitReference() : level.getDebitReference() + "#W" + w;
            try {
                flow.kit().journal().released(ctx.sessionId(), reference);
            } catch (RuntimeException e) {
                log.warn("[{}] {} | the journal of the calls in the air did not take the settled reserve {} (a restart would try to give it back; the ledger moves money once per reference): {}",
                    flow.name(), LogText.clean(ctx.sessionId()), LogText.clean(reference), LogText.failureText(e));
            }
        }
    }

    /** The same tier, partner and rate on the next account; its reference and its first reserve come with the first window. */
    private static LevelAdmission freshLevel(LevelAdmission old, Long account) {
        LevelAdmission fresh = new LevelAdmission(old.getLevelIndex(), old.getTenant(), old.getPartner(), null);
        fresh.setRate(old.getRate());
        if (old.getUom() != null) fresh.setUom(old.getUom());
        fresh.setRatePrefix(old.getRatePrefix());
        fresh.setUsageKind(old.getUsageKind());
        fresh.setUsageSeconds(old.getUsageSeconds());
        fresh.setReservedAmount(BigDecimal.ZERO);
        fresh.setChargeAccountId(account);
        return fresh;
    }

    private static void replace(RoutesphereContext ctx, LevelAdmission old, LevelAdmission fresh) {
        List<LevelAdmission> levels = new ArrayList<>(ctx.levels);
        levels.set(levels.indexOf(old), fresh);
        ctx.levels = List.copyOf(levels);
    }

    private boolean firstWindowHeld(C ctx, LevelAdmission fresh, Long next, String refusal) {
        if (refusal == null || SessionCause.BILLING_SYSTEM_ERROR.equals(refusal)) {
            ctx.history.note(flow.name(), "tier " + fresh.getLevelIndex() + ": the session goes on, on account " + next + " under " + fresh.getDebitReference()
                + " (span " + ctx.spanNo() + ")");
            return true;
        }
        ctx.history.note(flow.name(), "tier " + fresh.getLevelIndex() + ": the next account " + next + " could not fund its first window (" + refusal + ") — the cut");
        return false;
    }

    static Long accountOf(LevelAdmission level) {
        return level.getPackageAccountId() != null ? level.getPackageAccountId() : level.getChargeAccountId();
    }
}
