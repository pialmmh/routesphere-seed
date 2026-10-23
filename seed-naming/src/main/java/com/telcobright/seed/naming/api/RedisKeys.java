package com.telcobright.seed.naming.api;

/**
 * Redis — the topic's equivalent (R9): a key or a stream of an application of a root is {@code <root>:<app>:<name>}
 * ({@code link3:ad-sphere:quota}). A part with a {@code :} or a blank part is refused: the key would read as another root's or another
 * application's. Redis needs nothing created; the name is the namespace.
 */
public final class RedisKeys {

    private RedisKeys() {}

    /** {@code <root>:<app>:<name>}. */
    public static String of(String root, String app, String name) { return part("root", root) + ":" + part("app", app) + ":" + part("name", name); }

    private static String part(String what, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("a Redis key's " + what + " is missing");
        if (value.indexOf(':') >= 0 || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("a Redis key's " + what + " '" + value + "' has a ':' or a space: the key would read as another root's or application's");
        }
        return value;
    }
}
