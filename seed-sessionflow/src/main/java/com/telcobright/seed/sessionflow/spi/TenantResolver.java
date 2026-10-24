package com.telcobright.seed.sessionflow.spi;

import com.telcobright.seed.sessionflow.api.Ingress;

import java.util.Optional;

/**
 * Which ROOT tenant did this session come in for? A lane's step (a) {@code identifyRootLevelTenant} asks it (through the base's helpers) with the ingress the
 * signaling machine stamped on the facts ({@code listen ip:port}, {@code esl host:port}, {@code vlan gateway:svlan}, {@code topic name}).
 * The answer is the root's tenant CODE (the rule's {@code tenant}). No match = empty, NEVER a default: a session nobody claims is refused
 * {@code NO_TENANT}, not quietly given to someone (operator model §5; ARCH-0077-A item 8).
 */
@FunctionalInterface
public interface TenantResolver {

    Optional<String> tenantOf(Ingress ingress);
}
