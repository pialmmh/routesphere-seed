package com.telcobright.seed.tenant.internal;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.tenant.spi.TreeSource;

import java.io.IOException;

/**
 * ONE place that turns a fetched tree into a walkable one: the root must name its database; the flat index ({@code tenantIndex})
 * and the ancestor chains (leaf → root, walked by each node's {@code parent} NAME through that index) are not on the wire and are
 * rebuilt here — after every fetch, from every source. The mapper is the one the call switch's tree needs: fields of any visibility,
 * unknown properties ignored (the wire carries more than the model names).
 */
public final class Trees {
    private Trees() {}

    public static ObjectMapper treeMapper() {
        return new ObjectMapper()
            .findAndRegisterModules()
            .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** A tree out of its JSON, ready to walk. */
    public static Tenant parse(String json) {
        Tenant root;
        try {
            root = treeMapper().readValue(json, Tenant.class);
        } catch (IOException e) {
            throw new TreeSource.TreeUnavailable("the tenant tree could not be parsed: " + e.getMessage(), e);
        }
        return ready(root);
    }

    /** A fetched tree made walkable; a tree without a root database name is refused, never served. */
    public static Tenant ready(Tenant root) {
        if (root == null || root.getDbName() == null || root.getDbName().isBlank()) throw new TreeSource.TreeUnavailable("the tenant tree has no root dbName");
        root.rebuildTenantIndex();
        root.computeAncestorChains();
        return root;
    }
}
