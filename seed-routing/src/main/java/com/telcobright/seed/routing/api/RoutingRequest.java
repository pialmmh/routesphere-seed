package com.telcobright.seed.routing.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One request that must be sent on — a call, an SMS, a payment, an ad view — as the router sees it: the
 * DOMAIN it belongs to and a flat map of ATTRIBUTES. The attribute names are the product's vocabulary
 * (call/SMS: {@code partner, source, called, calling, tenant}; payment: {@code partner, app, zone, site,
 * method, amount, currency}); the router never interprets them beyond matching, so one mechanism serves
 * every switch. Keys are lower-case; a blank value is "absent".
 */
public record RoutingRequest(String domain, Map<String, String> attributes) {

    public RoutingRequest {
        domain = domain == null ? "" : domain.trim().toLowerCase(Locale.ROOT);
        Map<String, String> clean = new LinkedHashMap<>();
        if (attributes != null) {
            attributes.forEach((k, v) -> {
                if (k != null && v != null && !v.isBlank()) clean.put(k.trim().toLowerCase(Locale.ROOT), v.trim());
            });
        }
        attributes = Collections.unmodifiableMap(clean);
    }

    /** The attribute's value, or null when the request does not carry it. */
    public String get(String name) {
        return name == null ? null : attributes.get(name.trim().toLowerCase(Locale.ROOT));
    }

    public static Builder of(String domain) { return new Builder(domain); }

    public static final class Builder {
        private final String domain;
        private final Map<String, String> attrs = new LinkedHashMap<>();
        private Builder(String domain) { this.domain = domain; }
        public Builder with(String name, Object value) {
            if (name != null && value != null) attrs.put(name, String.valueOf(value));
            return this;
        }
        public RoutingRequest build() { return new RoutingRequest(domain, attrs); }
    }
}
