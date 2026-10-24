package probe;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.Config;

/** A @Startup bean a REST resource and a request filter reach through Instance<> (lazily), not by a field of its own type. */
@Startup
@Singleton
public class LazyStartupBean {
    @Inject Config config;
    @PostConstruct void start() { Seen.read("@Startup bean a REST resource reaches by Instance<>", config.getValue("probe.value", String.class)); }
    public String value() { return config.getValue("probe.value", String.class); }
}
