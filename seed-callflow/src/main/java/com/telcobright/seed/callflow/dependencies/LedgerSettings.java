package com.telcobright.seed.callflow.dependencies;

import java.util.Objects;
import java.util.function.Function;

/**
 * How the ledger client reaches orchestrix portal-api (the consumer contract §0): the base URL, the NAME of the environment
 * variable holding the bearer (S20: the value never leaves the box — a missing NAME or an empty value refuses the start
 * naming the variable), the tenant of every call ({@code X-Tenant-Id}), the profile in the road ({@code ad-credit}), the
 * connect and read timeouts (500 ms and 1,500 ms when not said: a call's admission waits for this ledger, and a
 * subscriber waits for the admission).
 */
public record LedgerSettings(String baseUrl, String tokenVar, String tenant, String profile, long connectTimeoutMs, long readTimeoutMs, String by) {

    public static final long DEFAULT_CONNECT_TIMEOUT_MS = 500;
    public static final long DEFAULT_READ_TIMEOUT_MS = 1500;

    public LedgerSettings {
        Objects.requireNonNull(baseUrl, "baseUrl");
        if (tokenVar == null || tokenVar.isBlank()) throw new IllegalStateException("the ledger's bearer needs the NAME of its environment variable (prepaid.portalApi.tokenVar) — none given");
        if (tenant == null || tenant.isBlank()) throw new IllegalStateException("the ledger's X-Tenant-Id (prepaid.portalApi.tenant) is empty");
        if (profile == null || profile.isBlank()) profile = "ad-credit";
        if (connectTimeoutMs <= 0) connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
        if (readTimeoutMs <= 0) readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
        if (by == null || by.isBlank()) by = "ad-sphere";
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public static LedgerSettings of(String baseUrl, String tokenVar, String tenant) {
        return new LedgerSettings(baseUrl, tokenVar, tenant, "ad-credit", DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS, "ad-sphere");
    }

    /** The bearer's VALUE from the environment, by the NAME; an empty value refuses the start naming the variable. */
    public String bearer(Function<String, String> environment) {
        String v = environment.apply(tokenVar);
        if (v == null || v.isBlank()) throw new IllegalStateException("the ledger's bearer variable " + tokenVar + " is empty — the start is refused (S20)");
        return v.trim();
    }

    public String roads() { return baseUrl + "/api/v1/prepaid/" + profile; }
}
