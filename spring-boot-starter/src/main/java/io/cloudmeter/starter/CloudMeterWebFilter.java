package io.cloudmeter.starter;

import io.cloudmeter.collector.MetricsStore;
import io.cloudmeter.collector.RequestMetrics;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * WebFlux {@link WebFilter} that captures per-request cost metrics for CloudMeter.
 *
 * <p>Installed automatically when Spring WebFlux is on the classpath. No
 * {@code -javaagent} flag is needed for WebFlux applications — this filter
 * provides the instrumentation layer.
 *
 * <p><strong>CPU attribution:</strong> Reactive request handling is inherently
 * multi-threaded and non-blocking. CloudMeter records wall-clock duration but
 * sets CPU core-seconds to zero for WebFlux requests. Cost projections for
 * WebFlux are based on instance-hours (wall-clock) rather than CPU-seconds.
 *
 * <p><strong>Route normalisation:</strong> Uses Spring's
 * {@code HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE} so that
 * {@code /api/users/123} and {@code /api/users/456} both map to
 * {@code GET /api/users/{id}}.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CloudMeterWebFilter implements WebFilter {

    static final String PATTERN_ATTR =
            "org.springframework.web.reactive.HandlerMapping.bestMatchingPattern";

    private final MetricsStore store;
    private final long agentStartMs = System.currentTimeMillis();
    private static final long WARMUP_MS = 30_000;

    CloudMeterWebFilter(MetricsStore store) {
        this.store = store;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long startNanos = System.nanoTime();
        return chain.filter(exchange).doFinally(signal -> record(exchange, startNanos));
    }

    private void record(ServerWebExchange exchange, long startNanos) {
        try {
            String method = exchange.getRequest().getMethod().name();
            String path   = exchange.getRequest().getPath().value();

            Object patternAttr = exchange.getAttribute(PATTERN_ATTR);
            String route = (patternAttr instanceof PathPattern)
                    ? method + " " + ((PathPattern) patternAttr).getPatternString()
                    : method + " " + path;

            ServerHttpResponse response = exchange.getResponse();
            int status = response.getStatusCode() != null
                    ? response.getStatusCode().value() : 200;

            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;

            long egressBytes = 0;
            String cl = response.getHeaders().getFirst("Content-Length");
            if (cl != null) {
                try { egressBytes = Long.parseLong(cl); } catch (NumberFormatException ignored) {}
            }

            boolean warmup = (System.currentTimeMillis() - agentStartMs) < WARMUP_MS;

            RequestMetrics metrics = RequestMetrics.builder()
                    .routeTemplate(route)
                    .actualPath(method + " " + path)
                    .httpMethod(method)
                    .httpStatusCode(status)
                    .durationMs(durationMs)
                    .cpuCoreSeconds(0.0)   // not measurable in reactive
                    .peakMemoryBytes(0L)
                    .egressBytes(egressBytes)
                    .threadWaitRatio(0.0)
                    .timestamp(Instant.now())
                    .warmup(warmup)
                    .build();

            store.add(metrics);
        } catch (Exception ignored) {
            // Never let instrumentation crash the request
        }
    }
}
