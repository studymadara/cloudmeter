package io.cloudmeter.smoketest;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * A servlet filter that adds a response header and does a small amount of CPU work
 * for every request. Included to verify that CloudMeter attributes filter-chain
 * overhead correctly to the request context — the cost should appear in the
 * endpoint metrics, not be lost when execution passes through the filter.
 */
@Component
public class CostTrackingFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        // Small CPU burn before the controller executes — should show up in endpoint cost
        long checksum = 0;
        for (int i = 0; i < 50_000; i++) {
            checksum = checksum * 6364136223846793005L + 1;
        }

        chain.doFilter(request, response);

        // Tag response so callers can confirm the filter ran
        if (response instanceof HttpServletResponse) {
            ((HttpServletResponse) response).setHeader("X-CloudMeter-Filter", "ok-" + (checksum & 0xffff));
        }
    }
}
