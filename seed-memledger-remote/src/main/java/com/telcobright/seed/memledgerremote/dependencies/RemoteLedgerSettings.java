package com.telcobright.seed.memledgerremote.dependencies;

/**
 * The words of a root whose ledger says {@code store: remote}: {@code address} (host:port of its mem-ledger server, on the host's 10.10
 * address), {@code deadline-ms} (the bound of every call and the port's {@code slowestAnswerMs()}; 2000 unless said), {@code token-var}
 * (the NAME of the environment variable holding the bearer — the value is read once at the start by the source and never held here).
 */
public record RemoteLedgerSettings(String address, long deadlineMs, String tokenVar) {

    public static final long DEFAULT_DEADLINE_MS = 2_000;

    public RemoteLedgerSettings {
        if (address == null || address.isBlank()) throw new IllegalArgumentException("ledger.address names the mem-ledger server as host:port");
        if (deadlineMs <= 0) throw new IllegalArgumentException("ledger.deadline-ms is a positive number of milliseconds, not " + deadlineMs);
    }
}
