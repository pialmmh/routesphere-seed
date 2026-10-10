package com.telcobright.seed.sessionflow.api;

/**
 * One identification rule of the facade's bootstrap (prime-context F4: {@code identification: [{kind, match, tenant}]}): a request whose
 * fact of {@code kind} is exactly {@code match} came in for {@code tenant} — the ROOT of a served tree, by its database name.
 */
public record IdentificationRule(String kind, String match, String tenant) {

    public IdentificationRule {
        if (kind == null || kind.isBlank()) throw new IllegalArgumentException("an identification rule needs its kind (listen, esl)");
        if (match == null || match.isBlank()) throw new IllegalArgumentException("an identification rule of kind " + kind + " needs its match");
        if (tenant == null || tenant.isBlank()) throw new IllegalArgumentException("the identification rule " + kind + " " + match + " names no tenant");
    }

    @Override
    public String toString() { return kind + " " + match + " → " + tenant; }
}
