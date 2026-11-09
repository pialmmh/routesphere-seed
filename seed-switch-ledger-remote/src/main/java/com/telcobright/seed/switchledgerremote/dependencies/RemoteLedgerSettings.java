package com.telcobright.seed.switchledgerremote.dependencies;

/**
 * The words of a root whose {@code ledger.kind = remote}: {@code address} (host:port of the switch-ledger service, on the host's 10.10
 * address), {@code deadline-ms} (the bound of every call and the port's {@code slowestAnswerMs()}; 2000 unless said), {@code token-var}
 * (the NAME of the environment variable holding the bearer — the value is read once at start by the maker and never held here).
 */
public record RemoteLedgerSettings(String address, long deadlineMs, String tokenVar) {

    public static final long DEFAULT_DEADLINE_MS = 2_000;

    public RemoteLedgerSettings {
        if (address == null || address.isBlank()) throw new IllegalArgumentException("ledger.address names the switch-ledger service as host:port");
        if (deadlineMs <= 0) throw new IllegalArgumentException("ledger.deadline-ms is a positive number of milliseconds, not " + deadlineMs);
    }

    public static RemoteLedgerSettings of(String address) { return new RemoteLedgerSettings(address, DEFAULT_DEADLINE_MS, null); }
}
