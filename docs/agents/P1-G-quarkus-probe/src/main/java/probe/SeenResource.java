package probe;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/seen")
public class SeenResource {
    @GET public String seen() { return "factory calls " + ProbeFactory.CALLS + "\n" + Seen.BY_READER + "\n"; }
}
