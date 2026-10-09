package com.telcobright.seed.idgen.api;

/**
 * The id service refused the request itself (a 4xx in its words: {@code Type mismatch}, {@code Invalid startValue}, …). Another
 * shard would answer the same, so no other shard is asked. Nothing was minted.
 */
public final class IdRefused extends RuntimeException {
    private final int status;
    private final String code;

    public IdRefused(int status, String code, String message) {
        super("the id service refused (" + status + " " + code + "): " + message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }

    /** The service's own {@code error} word. */
    public String code() { return code; }
}
