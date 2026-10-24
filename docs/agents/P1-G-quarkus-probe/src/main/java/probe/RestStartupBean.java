package probe;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.Config;

/** A @Startup bean a REST resource and a request filter inject — as ad-sphere's AdSphereRuntime is. */
@Startup
@Singleton
public class RestStartupBean {
    @Inject Config config;
    @PostConstruct void start() { Seen.read("@Startup bean a REST resource injects", config.getValue("probe.value", String.class)); }
    public String value() { return config.getValue("probe.value", String.class); }
}
