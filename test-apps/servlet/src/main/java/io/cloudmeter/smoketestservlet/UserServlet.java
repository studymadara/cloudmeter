package io.cloudmeter.smoketestservlet;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * GET /api/users/{id} — light CPU work, simulates a DB lookup.
 * Path info is everything after /api/users, e.g. "/42" → id="42".
 */
public class UserServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        String pathInfo = req.getPathInfo();
        String id = (pathInfo != null && pathInfo.length() > 1)
                ? pathInfo.substring(1)
                : "unknown";

        long checksum = cpuWork(1_000_000);

        resp.setContentType("application/json");
        resp.setStatus(200);
        resp.getWriter().write(
                "{\"id\":\"" + id + "\","
                + "\"name\":\"User " + id + "\","
                + "\"email\":\"user" + id + "@example.com\","
                + "\"checksum\":" + checksum + "}"
        );
    }

    private static long cpuWork(int iterations) {
        long acc = ThreadLocalRandom.current().nextLong();
        for (int i = 0; i < iterations; i++) {
            acc = acc * 6364136223846793005L + 1442695040888963407L;
        }
        return acc;
    }
}
