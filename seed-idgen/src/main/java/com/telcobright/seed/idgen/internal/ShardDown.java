package com.telcobright.seed.idgen.internal;

/** This shard cannot serve the call (down, timed out, failed on its side, out of values): the next shard is asked. */
final class ShardDown extends RuntimeException {
    ShardDown(String message) { super(message); }
}
