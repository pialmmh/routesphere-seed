package probe;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

public class ProbeFilter {
    @Inject RestStartupBean bean;
    @ServerRequestFilter public void filter(ContainerRequestContext ctx) { bean.value(); }
}
