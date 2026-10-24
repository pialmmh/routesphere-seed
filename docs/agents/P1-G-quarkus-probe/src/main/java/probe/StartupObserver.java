package probe;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.ConfigProvider;

@ApplicationScoped
public class StartupObserver {
    void onStart(@Observes StartupEvent event) { Seen.read("@Observes StartupEvent (ConfigProvider.getConfig())", ConfigProvider.getConfig().getValue("probe.value", String.class)); }
}
