package com.telcobright.seed.switchledger.internal;

import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccountReserve;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;

/**
 * The reaper of the reserve rows a dead session left behind (ARCH-0077-A item 4) — routesphere-core
 * {@code PrepaidServiceWithCompensation.reapOrphanReserves}, lines 533–576: "a reserve whose call ended but never settled (crash / SIGKILL /
 * timeout …) leaves a packageaccountreserve row behind, with its reserveUnit debited from balance but never returned. This releases such
 * orphans via the normal returnBalance path". Only rows OLDER than {@code reaperMaxAgeMinutes} are touched (548–555: "that threshold must
 * exceed the maximum possible call duration, so an in-flight call's reserve is never released"); a row whose time cannot be read is never
 * touched (550–554: "don't release something we can't date"); at most {@code reaperBatchLimit} rows per pass (547). For the host's schedule
 * (the base's slot-reconcile cadence), one call per tier schema; {@code SwitchLedgers.reaper(…)} hands it out.
 */
public final class OrphanReaper {

    private static final Logger log = LoggerFactory.getLogger(OrphanReaper.class);

    private final Books books;
    private final AccountLocks locks;
    private final SwitchLedgerSettings settings;
    private final Clock clock;

    public OrphanReaper(Books books, AccountLocks locks, SwitchLedgerSettings settings, Clock clock) {
        this.books = books;
        this.locks = locks;
        this.settings = settings;
        this.clock = clock;
    }

    /** @return how many orphans this pass gave back (at most {@code reaperBatchLimit}) */
    public int reap(String dbName) {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusMinutes(settings.reaperMaxAgeMinutes());
        int released = 0;
        for (PackageAccountReserve row : new ArrayList<>(books.reserveRows(dbName).values())) {
            if (released >= settings.reaperBatchLimit()) break;
            if (isOrphan(row, cutoff) && giveBack(dbName, row)) released++;
        }
        if (released > 0) log.warn("the reaper gave back {} orphaned reserve(s) in {} (older than {} min)", released, dbName, settings.reaperMaxAgeMinutes());
        return released;
    }

    /** An orphan is dated, and dated before the cutoff; a row with no readable time is left where it is. */
    private static boolean isOrphan(PackageAccountReserve row, LocalDateTime cutoff) {
        if (row == null || row.getTime() == null || row.getIdPackageAccount() == null) return false;
        try {
            return !LocalDateTime.parse(row.getTime()).isAfter(cutoff);
        } catch (DateTimeParseException unreadable) {
            return false;
        }
    }

    /** Under the account's lock the row is read again — a session may have settled it meanwhile — then given back whole. */
    private boolean giveBack(String dbName, PackageAccountReserve row) {
        try (AccountLocks.Held held = locks.hold(dbName, row.getIdPackageAccount())) {
            PackageAccountReserve still = books.reserveRow(dbName, row.getChannelCallUuid());
            if (still == null) return false;
            books.returnRow(dbName, still);
            return true;
        } catch (RuntimeException e) {
            log.error("the reaper could not give back the reserve {} of {}: {}", row.getChannelCallUuid(), dbName, e.toString());
            return false;
        }
    }
}
