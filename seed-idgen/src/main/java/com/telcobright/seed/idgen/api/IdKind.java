package com.telcobright.seed.idgen.api;

/**
 * The id types of bucket-next, as its {@code dataType} names them. An entity is bound to one type on its first call, for life.
 * {@code INT} and {@code LONG} are counters (shard k of N hands out k, k+N, k+2N, …) and may be started above a value; the others
 * are not.
 */
public enum IdKind {
    INT("int"), LONG("long"), SNOWFLAKE("snowflake"), UUID8("uuid8"), UUID12("uuid12"), UUID16("uuid16"), UUID22("uuid22");

    private final String wire;

    IdKind(String wire) { this.wire = wire; }

    /** The {@code dataType} word on the wire. */
    public String wire() { return wire; }

    /** A counter: it can be registered with a start value and moved forward. */
    public boolean counter() { return this == INT || this == LONG; }

    /** The largest value a counter of this kind may hold. */
    public long max() { return this == INT ? Integer.MAX_VALUE : Long.MAX_VALUE; }
}
