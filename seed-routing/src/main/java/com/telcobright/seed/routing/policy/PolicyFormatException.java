package com.telcobright.seed.routing.policy;

/** The policy document is wrong, and the message says WHERE (a path into the JSON) in words an officer can act on. */
public class PolicyFormatException extends RuntimeException {
    public PolicyFormatException(String message) { super(message); }
}
