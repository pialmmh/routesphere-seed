package com.telcobright.seed.sessionflow.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

/**
 * prime-context's word that a tenant was created (R10, §C.2): on the platform topic {@code tenant_created}, keyed by the root —
 * {@code {"root", "tierDb", "parentDb", "partnerId", "kind", "apps", "at"}}. {@code kind} {@code root}: a new operator ({@code tierDb} = the
 * root, no parent, no partner); {@code reseller}: a new tier under a root. {@code apps}: the applications that serve it.
 */
public record TenantCreated(String root, String tierDb, String parentDb, Integer partnerId, String kind, List<String> apps, String at) {

    public static final String ROOT = "root", RESELLER = "reseller";

    private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public TenantCreated { apps = apps == null ? List.of() : List.copyOf(apps); }

    /** The message's value; refused in words when it is not one or names no root. */
    public static TenantCreated parse(String json) {
        TenantCreated event;
        try {
            event = JSON.readValue(json, TenantCreated.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("a tenant_created message that is not §C.2's JSON: " + e.getMessage(), e);
        }
        if (event.root() == null || event.root().isBlank()) throw new IllegalArgumentException("a tenant_created message names no root: " + json);
        return event;
    }

    /** A new operator (a root), not a tier under one. */
    public boolean isRoot() { return ROOT.equals(kind) || (kind == null && root.equals(tierDb)); }

    /** Is this application one that serves it (no list = every application of the root). */
    public boolean names(String app) { return apps.isEmpty() || apps.contains(app); }

    /** The event's identity: the same tenant created twice is one event. */
    public String identity() { return (isRoot() ? ROOT : RESELLER) + ":" + root + ":" + (tierDb == null ? root : tierDb); }
}
