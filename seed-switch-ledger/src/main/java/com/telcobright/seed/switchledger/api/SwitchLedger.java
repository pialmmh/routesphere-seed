package com.telcobright.seed.switchledger.api;

import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.switchledger.internal.OrphanReaper;

/**
 * The doors of ONE switch ledger over ONE MemLedger (ARCH-0077-A): the base's port (reserve · settle · release), the live-balance peek, the
 * orphan reaper, the one credit primitive.
 * Built by {@code SwitchLedgers.over(memLedger, settings, clock)}; every door moves an account's money under the same per-account lock.
 *
 * @param port     the base's {@link LedgerPort}: what the kit is handed
 * @param balances the read of a tier's live balance (the C14 remainder's peek)
 * @param reaper   the release of the reserve rows a dead session left behind, for the host's schedule
 * @param credit   the one credit primitive (a recharge road's), on the same lock as the port
 */
public record SwitchLedger(LedgerPort port, LiveBalance balances, OrphanReaper reaper, Credit credit) {}
