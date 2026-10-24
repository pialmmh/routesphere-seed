package probe;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/lazy")
public class LazyResource {
    @Inject Instance<LazyStartupBean> bean;
    @GET public String value() { return bean.get().value(); }
}
