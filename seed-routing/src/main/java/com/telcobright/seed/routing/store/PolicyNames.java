package com.telcobright.seed.routing.store;

import com.telcobright.seed.routing.policy.PolicyFormatException;
import com.telcobright.seed.routing.policy.RoutingPolicy;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A policy's name is an IDENTIFIER: a config key holds it ({@code routing.policies.<name>.document}), a URL
 * carries it, a record stores it. So it is lower case, starts with a letter or a digit, and holds only letters,
 * digits, dash and underscore — never a dot (a dot would split the config key).
 */
final class PolicyNames {
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,127}");
    private static final Pattern DOMAIN = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

    private PolicyNames() {}

    static void check(RoutingPolicy p) {
        if (!DOMAIN.matcher(p.domain()).matches()) throw new PolicyFormatException("domain: '" + p.domain() + "' is not a domain word (call, sms, payment, ad …)");
        if (!NAME.matcher(p.name().toLowerCase(Locale.ROOT)).matches()) {
            throw new PolicyFormatException("name: '" + p.name() + "' — use lower-case letters, digits, dash, underscore (at most 128), starting with a letter or a digit");
        }
    }

    static RoutingPolicy normalised(RoutingPolicy p) {
        return new RoutingPolicy(p.domain(), p.name().toLowerCase(Locale.ROOT), p.type(), p.enabled(), p.version(), p.description(),
            p.document(), p.updatedAt(), p.updatedBy());
    }
}
