package com.telcobright.seed.sessionflow.spi;

import com.telcobright.seed.sessionflow.api.RequestFacts;

import java.util.Optional;

/**
 * Which tenant did this request come in for? Asked by the PRODUCT, before admission: it sets the flow's {@code ctx.tenantName} from the
 * answer, and the base's own {@code resolveTenant} then checks that the name is the root of a served tree (it stays as it is). No match =
 * empty, NEVER a default: a request nobody claims is refused, not quietly given to someone (ARCH-0077-A item 8).
 */
@FunctionalInterface
public interface TenantResolver {

    Optional<String> tenantOf(RequestFacts facts);
}
