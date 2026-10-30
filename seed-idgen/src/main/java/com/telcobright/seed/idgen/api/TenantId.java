package com.telcobright.seed.idgen.api;

import java.util.regex.Pattern;

/**
 * The tenant word every id call carries (the owner, 2026-10-30: one id service per installation, serving every tenant, a tenant id
 * mandatory on every call). It is tenant-master's tenant id — the word a root carries: {@code btcl} = the root schema = the topic
 * suffix = the tenant id — {@code ^[a-z][a-z0-9]{1,15}$}, never case-folded. The caller passes it on every call; the client has no
 * default tenant, and a blank or ill-formed one is refused here, in words, before any call leaves the process.
 */
public final class TenantId {

    /** The rule of tenant-master's tenant id (prime-context {@code TenantRegistry.PLATFORM_TENANT_ID}; the id service's own check). */
    public static final Pattern RULE = Pattern.compile("[a-z][a-z0-9]{1,15}");

    private TenantId() {}

    /** {@code tenant} as given when it keeps the rule; {@code IllegalArgumentException} in words otherwise (null and blank included). */
    public static String checked(String tenant) {
        if (tenant == null || !RULE.matcher(tenant).matches()) {
            throw new IllegalArgumentException("a tenant is tenant-master's tenant id ([a-z][a-z0-9]{1,15}: lowercase letters and digits, a letter first, "
                + "2-16 characters, never case-folded), not '" + tenant + "' — the caller passes the root's tenant on every call; there is no default");
        }
        return tenant;
    }
}
