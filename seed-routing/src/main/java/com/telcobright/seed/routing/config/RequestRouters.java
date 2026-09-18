package com.telcobright.seed.routing.config;

import com.telcobright.seed.routing.api.RequestRouter;
import com.telcobright.seed.routing.routers.dialplan.DialplanRouter;
import com.telcobright.seed.routing.routers.policy.PolicyRouter;
import com.telcobright.seed.routing.spi.DialplanSource;
import com.telcobright.seed.routing.spi.RouteDirectory;
import com.telcobright.seed.routing.store.PolicyCatalog;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * CONFIG picks the router — the one place where "which bean routes this domain" is decided:
 *
 * <pre>
 *   routing.&lt;domain&gt;.type   set      → that router type
 *   routing.&lt;domain&gt;.policy set      → policy routing with that policy
 *   neither                          → the domain's default (call, sms: dialplan)
 * </pre>
 *
 * Router types are a registry ({@code dialplan}, {@code policy}; a host may add its own), never a code branch in
 * a product. A Quarkus host exposes the result with one producer method — the house pattern of
 * {@code TenantTopicResolverProducer}:
 *
 * <pre>
 *   &#64;Produces &#64;ApplicationScoped &#64;Named("smsRouter")
 *   RequestRouter smsRouter() { return routers.create("sms", settings, RequestRouters.DIALPLAN, parts); }
 * </pre>
 */
public final class RequestRouters {
    public static final String DIALPLAN = DialplanRouter.TYPE;
    public static final String POLICY = PolicyRouter.TYPE;

    /** What a router may need; a part a deployment does not have stays null and a router that needs it says so. */
    public record Parts(PolicyCatalog catalog, RouteDirectory directory, DialplanSource dialplan) {}

    /** Builds one router type for one domain. */
    public interface Maker {
        RequestRouter make(String domain, RoutingSettings.Domain settings, Parts parts);
    }

    private final Map<String, Maker> makers = new ConcurrentSkipListMap<>();

    /** The standard set: {@code dialplan} and {@code policy}. */
    public static RequestRouters standard() {
        RequestRouters r = new RequestRouters();
        r.register(DIALPLAN, (domain, settings, parts) -> {
            if (parts.dialplan() == null) throw new IllegalStateException("routing." + domain + ": dialplan routing needs a dialplan source, and this service has none");
            return new DialplanRouter(parts.dialplan(), parts.directory());
        });
        r.register(POLICY, (domain, settings, parts) -> {
            if (parts.catalog() == null) throw new IllegalStateException("routing." + domain + ": policy routing needs a policy catalog, and this service has none");
            if (!settings.namesPolicy()) throw new IllegalStateException("routing." + domain + ".policy is blank — policy routing needs the policy's name");
            return new PolicyRouter(domain, settings.policy(), parts.catalog(), parts.directory());
        });
        return r;
    }

    public RequestRouters register(String type, Maker maker) {
        makers.put(type.trim().toLowerCase(Locale.ROOT), maker);
        return this;
    }

    public Set<String> known() { return makers.keySet(); }

    /**
     * @param defaultType what routes the domain when the config names neither a type nor a policy
     *                    (call and SMS: {@link #DIALPLAN})
     */
    public RequestRouter create(String domain, RoutingSettings settings, String defaultType, Parts parts) {
        RoutingSettings.Domain d = settings.domain(domain);
        String type = !d.type().isBlank() ? d.type() : d.namesPolicy() ? POLICY : defaultType;
        Maker maker = makers.get(type == null ? "" : type.trim().toLowerCase(Locale.ROOT));
        if (maker == null) throw new IllegalStateException("routing." + domain + ".type: '" + type + "' is not a router type (known: " + makers.keySet() + ")");
        return maker.make(d.name(), d, parts);
    }
}
