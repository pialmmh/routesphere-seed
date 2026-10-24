package probe;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

public class LazyFilter {
    @Inject Instance<LazyStartupBean> bean;
    @ServerRequestFilter public void filter(ContainerRequestContext ctx) { bean.get(); }
}
