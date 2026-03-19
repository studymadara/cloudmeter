package io.cloudmeter.smoketestsb2;

import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/api")
public class SmokeTestController2 {

    private final AsyncWorkService asyncWorkService;

    public SmokeTestController2(AsyncWorkService asyncWorkService) {
        this.asyncWorkService = asyncWorkService;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return new HashMap<String, String>() {{ put("status", "ok"); }};
    }

    @GetMapping("/users/{id}")
    public Map<String, Object> getUser(@PathVariable String id) {
        long result = cpuWork(1_000_000);
        Map<String, Object> user = new HashMap<>();
        user.put("id", id);
        user.put("name", "User " + id);
        user.put("email", "user" + id + "@example.com");
        user.put("checksum", result);
        return user;
    }

    @PostMapping("/process")
    public Map<String, Object> process(@RequestBody(required = false) Map<String, Object> body) {
        long result = cpuWork(10_000_000);
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

    @GetMapping("/async-work")
    public Map<String, Object> asyncWork() {
        try {
            long servletResult = cpuWork(100_000);
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

    private static long cpuWork(int iterations) {
        long acc = ThreadLocalRandom.current().nextLong();
        for (int i = 0; i < iterations; i++) {
            acc = acc * 6364136223846793005L + 1442695040888963407L;
        }
        return acc;
    }
}
