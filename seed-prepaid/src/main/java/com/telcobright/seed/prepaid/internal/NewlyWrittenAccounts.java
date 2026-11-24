package com.telcobright.seed.prepaid.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.prepaid.api.PayingTier;
import com.telcobright.seed.sessionflow.api.LaneAnswers;
import com.telcobright.seed.sessionflow.api.LogText;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * I266 (the WiFi architect's acceptance, 2026-11-24) · An account a lane NAMES that the rule's list lacks may be a row another client wrote
 * after the ledger last looked — the BSS's purchase, written after its partner's first admission made the ledger's index. The walk asks the
 * ledger ({@link LedgerPort#mergeIfUnknown}): an id the cache knows (expired, of another unit or partner) costs no merge; an unknown one costs
 * ONE merge of the rows written since the last look. Either way, an id the ledger holds now — or any merge — builds the rule's list again.
 * <ul>
 *   <li>One merge per tier walk: it brings every row written since the last look, so the walk asks no more ids after it.</li>
 *   <li>An id still unknown after a merge is remembered for 60 s and not merged again inside that minute; it is dropped (S5: a lane narrows,
 *       it never widens), so the request ends on the lane's no-balance cause.</li>
 *   <li>A merge that fails is the store's failure (a fault on the wire: the admission's system fault), never "no balance".</li>
 *   <li>One line per merge — the tier, the id, found or not, the ms — in the log and in the session's history.</li>
 * </ul>
 * One per engine (the lane's): its memory serves every session of the lane.
 */
final class NewlyWrittenAccounts {

    private static final Logger log = LoggerFactory.getLogger(RoutesphereFlow.class);
    /** How long an id a merge did not find is not merged again. */
    static final long REMEMBERED_MS = 60_000;
    /** The memory is swept of its expired ids when it grows past this. */
    private static final int SWEEP_ABOVE = 10_000;

    /** "<tier schema>#<account id>" → until when (the kit's clock, ms) it is not merged again. */
    private final Map<String, Long> unknownUntil = new ConcurrentHashMap<>();

    /**
     * Asks the ledger for each of {@code unlisted} (the ids the lane named that the rule's list lacks), until one merge ran. True = build the
     * rule's list again: the ledger holds one of them now, or merged new rows.
     */
    <C extends RoutesphereContext> boolean buildTheListAgain(LaneAnswers<C> flow, C ctx, PayingTier tier, List<Long> unlisted) {
        if (unlisted.isEmpty()) return false;
        LevelAdmission level = new LevelAdmission(tier.levelIndex(), tier.tenant(), tier.partner(), null);
        boolean again = false;
        for (Long id : unlisted) {
            if (remembered(flow, tier.dbName(), id)) continue;
            LedgerPort.NewRows looked = askTheLedger(flow, ctx, tier, level, id);
            again |= looked.held() || looked.merged();
            if (looked.merged()) return again;
        }
        return again;
    }

    /** The ledger's answer for one id, a merge timed, said and remembered when it did not find the id. */
    private <C extends RoutesphereContext> LedgerPort.NewRows askTheLedger(LaneAnswers<C> flow, C ctx, PayingTier tier, LevelAdmission level, long id) {
        long started = System.nanoTime();
        LedgerPort.NewRows looked = mergedOrStoreFailure(flow, level, id);
        if (!looked.merged()) return looked;
        long ms = (System.nanoTime() - started) / 1_000_000;
        sayTheMerge(flow, ctx, tier, id, looked.held(), ms);
        if (!looked.held()) remember(flow, tier.dbName(), id);
        return looked;
    }

    /** The ledger's road; any failure of it is the store's (on the wire) — never a balance case. */
    private static <C extends RoutesphereContext> LedgerPort.NewRows mergedOrStoreFailure(LaneAnswers<C> flow, LevelAdmission level, long id) {
        try {
            LedgerPort.NewRows looked = flow.kit().ledger().mergeIfUnknown(level, id);
            return looked == null ? LedgerPort.NewRows.NOTHING_NEW : looked;
        } catch (LedgerPort.LedgerFault fault) {
            throw fault.onTheWire() ? fault : LedgerPort.LedgerFault.ofALedgerOnTheWire(fault.getMessage(), fault);
        } catch (RuntimeException e) {
            throw LedgerPort.LedgerFault.ofALedgerOnTheWire("the merge of the rows written since the ledger's last look failed (account " + id + " of "
                + level.getDbName() + "): " + e.getMessage(), e);
        }
    }

    private static <C extends RoutesphereContext> void sayTheMerge(LaneAnswers<C> flow, C ctx, PayingTier tier, long id, boolean found, long ms) {
        String line = "tier " + tier.levelIndex() + " (" + tier.dbName() + "): account " + id + " was unknown to the ledger's cache — merged the rows written"
            + " since its last look: found " + (found ? "yes" : "no") + " in " + ms + " ms";
        log.info("[{}] {} | {}", flow.name(), LogText.clean(ctx.sessionId()), line);
        ctx.history.note(flow.name(), line);
    }

    private <C extends RoutesphereContext> boolean remembered(LaneAnswers<C> flow, String db, long id) {
        Long until = unknownUntil.get(keyOf(db, id));
        if (until == null) return false;
        if (flow.kit().clock().millis() < until) return true;
        unknownUntil.remove(keyOf(db, id), until);
        return false;
    }

    private <C extends RoutesphereContext> void remember(LaneAnswers<C> flow, String db, long id) {
        long now = flow.kit().clock().millis();
        if (unknownUntil.size() >= SWEEP_ABOVE) unknownUntil.values().removeIf(until -> until <= now);
        unknownUntil.put(keyOf(db, id), now + REMEMBERED_MS);
    }

    private static String keyOf(String db, long id) { return db + "#" + id; }
}
