package com.telcobright.seed.switchledger.api;

import com.telcobright.seed.sessionflow.spi.LedgerPort;

/**
 * The doors of ONE switch ledger over ONE MemLedger (ARCH-0077-A): the base's port (reserve · settle · release), the live-balance peek.
 * Built by {@code SwitchLedgers.over(memLedger, settings, clock)}; every door moves an account's money under the same per-account lock.
 *
 * @param port     the base's {@link LedgerPort}: what the kit is handed
 * @param balances the read of a tier's live balance (the C14 remainder's peek)
 */
public record SwitchLedger(LedgerPort port, LiveBalance balances) {}
