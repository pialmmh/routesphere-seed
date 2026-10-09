package com.telcobright.seed.idgen.api;

import java.util.List;

/** No shard of the id service answered: each was down, timed out, failed on its side, or had no values left. Nothing was minted. */
public final class IdServiceUnavailable extends RuntimeException {
    private final List<String> failures;

    public IdServiceUnavailable(String entity, List<String> failures) {
        super("the id service minted nothing for '" + entity + "': no shard answered — " + String.join("; ", failures));
        this.failures = List.copyOf(failures);
    }

    /** One line per shard tried, in the order tried. */
    public List<String> failures() { return failures; }
}
