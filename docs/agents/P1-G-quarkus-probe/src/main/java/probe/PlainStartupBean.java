package probe;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.Config;

/** A @Startup bean nobody else injects. */
@Startup
@Singleton
public class PlainStartupBean {
    @Inject Config config;
    @PostConstruct void start() { Seen.read("plain @Startup bean", config.getValue("probe.value", String.class)); }
}
