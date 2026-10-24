package probe;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/probe")
public class ProbeResource {
    @Inject RestStartupBean bean;
    @GET public String value() { return bean.value(); }
}
