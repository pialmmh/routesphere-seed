package com.telcobright.seed.switchledgerremote.api;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.seed.switchledgerremote.internal.MemLedgerReads;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;

/**
 * The three account reads of design §3.3 (the WiFi's {@code LedgerAccounts}): an account by its id, the account of a purchase unexpired
 * at {@code now} ({@code Instant.EPOCH} = any), the ids of the accounts with an open reserve. Each answer carries the row and the expiry
 * the ledger knows for it (the purchase's where purchases are cached, else the row's own; null = unknown).
 */
public interface AccountReads {

    record Found(PackageAccount account, LocalDateTime expires) {}

    Optional<Found> accountById(String tierDb, long accountId);

    Optional<Found> accountOf(String tierDb, long purchaseId, Instant now);

    Set<Long> heldAccounts(String tierDb);

    /** The reads over the engine's own cache; {@code held} = the entity names it caches. */
    static AccountReads over(MemLedger ledger, Set<String> held, ZoneId zone) { return new MemLedgerReads(ledger, held, zone); }
}
