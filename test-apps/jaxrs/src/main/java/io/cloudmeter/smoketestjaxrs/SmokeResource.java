package io.cloudmeter.smoketestjaxrs;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * JAX-RS resource exposing three endpoints for smoke-testing CloudMeter agent instrumentation.
 *
 * Each endpoint performs a configurable amount of CPU work so the cost projection has
 * a measurable signal. Route templates (/api/users/{id}) let CloudMeter verify that
 * heuristic route normalization collapses individual IDs into a single template.
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class SmokeResource {

    @GET
    @Path("/health")
    public Map<String, String> health() {
        return Collections.singletonMap("status", "ok");
    }

    @GET
    @Path("/users/{id}")
    public Map<String, Object> getUser(@PathParam("id") String id) {
        long checksum = cpuWork(1_000_000);
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("name", "User " + id);
        m.put("checksum", checksum);
        return m;
    }

    @POST
    @Path("/process")
    public Map<String, Object> process() {
        long checksum = cpuWork(10_000_000);
        Map<String, Object> m = new HashMap<>();
        m.put("status", "processed");
        m.put("checksum", checksum);
        return m;
    }

    private static long cpuWork(int iterations) {
        long acc = ThreadLocalRandom.current().nextLong();
        for (int i = 0; i < iterations; i++) {
            acc = acc * 6364136223846793005L + 1442695040888963407L;
        }
        return acc;
    }
}
