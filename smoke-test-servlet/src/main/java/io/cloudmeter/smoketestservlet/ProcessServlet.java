package io.cloudmeter.smoketestservlet;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;

/** POST /api/process — heavy CPU + large response, highest cost endpoint. */
public class ProcessServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        long checksum = cpuWork(10_000_000);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("processed-data-chunk-").append(i).append(";");
        }

        resp.setContentType("application/json");
        resp.setStatus(200);
        resp.getWriter().write(
                "{\"status\":\"processed\","
                + "\"checksum\":" + checksum + ","
                + "\"output\":\"" + sb + "\"}"
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
