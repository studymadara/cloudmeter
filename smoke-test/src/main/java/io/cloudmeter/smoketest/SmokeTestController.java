package io.cloudmeter.smoketest;

import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Three endpoints with deliberately different cost profiles so CloudMeter
 * projections are visually distinct on the dashboard:
 *
 *   GET  /api/health       — instant; nearly zero cost
 *   GET  /api/users/{id}   — light CPU; small response
 *   POST /api/process      — heavier CPU + larger response; should be the priciest
 */
@RestController
@RequestMapping("/api")
public class SmokeTestController {

    /** GET /api/health — returns immediately, tiny response. */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    /**
     * GET /api/users/{id} — simulates a simple DB lookup.
     * Does a small amount of CPU work to produce a non-trivial cost signal.
     */
    @GetMapping("/users/{id}")
    public Map<String, Object> getUser(@PathVariable String id) {
        // Simulate 5–15 ms of CPU work (mimics a lightweight query)
        long result = cpuWork(5_000);

        Map<String, Object> user = new HashMap<>();
        user.put("id", id);
        user.put("name", "User " + id);
        user.put("email", "user" + id + "@example.com");
        user.put("checksum", result);  // keep the compiler from optimising away the work
        return user;
    }

    /**
     * POST /api/process — simulates a CPU-intensive operation (e.g. PDF export,
     * report generation). Much heavier than the other endpoints so it shows up
     * clearly as the top cost item on the CloudMeter dashboard.
     */
    @PostMapping("/process")
    public Map<String, Object> process(@RequestBody(required = false) Map<String, Object> body) {
        // Simulate 50–100 ms of CPU work
        long result = cpuWork(50_000);

        // Return a large-ish response to generate an egress signal
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("processed-data-chunk-").append(i).append(";");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "processed");
        response.put("checksum", result);
        response.put("output", sb.toString());
        return response;
    }

    /**
     * Burns CPU for approximately {@code iterations} tight-loop iterations.
     * Returns the final accumulator so the JIT cannot eliminate the loop.
     */
    private static long cpuWork(int iterations) {
        long acc = ThreadLocalRandom.current().nextLong();
        for (int i = 0; i < iterations; i++) {
            acc = acc * 6364136223846793005L + 1442695040888963407L;
        }
        return acc;
    }
}
