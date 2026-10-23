package com.telcobright.seed.naming.api;

/**
 * Which way an application's own topic runs (R9: the direction is in the name, and publish and subscribe never share a topic): {@link #IN}
 * = the application consumes it, {@link #OUT} = the application publishes it.
 */
public enum Direction {
    IN("in"), OUT("out");

    private final String word;

    Direction(String word) { this.word = word; }

    /** The word in the topic's name: {@code in} or {@code out}. */
    public String word() { return word; }
}
