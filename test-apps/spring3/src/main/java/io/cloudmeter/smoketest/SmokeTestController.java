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

    private final AsyncWorkService asyncWorkService;

    public SmokeTestController(AsyncWorkService asyncWorkService) {
        this.asyncWorkService = asyncWorkService;
    }

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
        long result = cpuWork(1_000_000);

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
        long result = cpuWork(10_000_000);

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
     * GET /api/async-work — submits heavy CPU work to a Spring @Async thread,
     * then blocks on the result before returning the response.
     *
     * This endpoint verifies CloudMeter's async context propagation:
     * - The ExecutorInstrumentation wraps the submitted Runnable in a
     *   ContextPropagatingRunnable so the worker thread's CPU is attributed
     *   to this request.
     * - Because we call .get() before the response is sent, onRequestEnd()
     *   fires after the async CPU is finalized — so it shows up in the metrics.
     * - Expected medianCpuMs should be visibly higher than /api/health (async
     *   thread does 25k iterations), confirming propagation works.
     */
    @GetMapping("/async-work")
    public Map<String, Object> asyncWork() {
        try {
            // Minimal work on servlet thread (to keep its CPU contribution small)
            long servletResult = cpuWork(100_000);

            // Heavy work on the @Async thread — we BLOCK until it's done so
            // the CPU contribution is captured before onRequestEnd() fires
            long asyncResult = asyncWorkService.doHeavyWork().get();

            Map<String, Object> response = new HashMap<>();
            response.put("status", "done");
            response.put("servletChecksum", servletResult);
            response.put("asyncChecksum", asyncResult);
            return response;
        } catch (Exception e) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", e.getMessage());
            return err;
        }
    }

    /**
     * GET /api/reports/{year}/{month} — custom multi-segment path template.
     * Verifies CloudMeter normalises nested path variables correctly so that
     * /api/reports/2024/01 and /api/reports/2025/12 map to the same route key.
     */
    @GetMapping("/reports/{year}/{month}")
    public Map<String, Object> report(@PathVariable String year, @PathVariable String month) {
        long result = cpuWork(3_000_000); // medium CPU — should show distinct cost from /users/{id}
        Map<String, Object> response = new HashMap<>();
        response.put("year", year);
        response.put("month", month);
        response.put("reportRows", 842);
        response.put("checksum", result);
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
