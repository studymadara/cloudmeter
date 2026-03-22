package io.cloudmeter.starter;

import io.cloudmeter.collector.MetricsStore;
import io.cloudmeter.collector.RequestMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CloudMeterWebFilterTest {

    private MetricsStore store;
    private CloudMeterWebFilter filter;

    @BeforeEach
    void setUp() {
        store = new MetricsStore(1000);
        store.startRecording();
        filter = new CloudMeterWebFilter(store);
    }

    private void invoke(String method, String path) throws InterruptedException {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.valueOf(method), path)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getResponse().setStatusCode(HttpStatus.OK);

        WebFilterChain chain = ex -> Mono.empty();
        filter.filter(exchange, chain).block();
        Thread.sleep(50); // let doFinally complete
    }

    @Test
    void filter_recordsMetricForEachRequest() throws InterruptedException {
        invoke("GET", "/api/health");
        List<RequestMetrics> all = store.getAll();
        assertFalse(all.isEmpty(), "Expected at least one metric to be recorded");
    }

    @Test
    void filter_recordsHttpMethod() throws InterruptedException {
        invoke("GET", "/api/health");
        List<RequestMetrics> all = store.getAll();
        assertTrue(all.stream().anyMatch(m -> "GET".equals(m.getHttpMethod())));
    }

    @Test
    void filter_recordsPositiveDuration() throws InterruptedException {
        invoke("GET", "/api/health");
        List<RequestMetrics> all = store.getAll();
        assertTrue(all.stream().anyMatch(m -> m.getDurationMs() >= 0));
    }

    @Test
    void filter_cpuCoreSecondsIsZero() throws InterruptedException {
        invoke("GET", "/api/health");
        List<RequestMetrics> all = store.getAll();
        assertTrue(all.stream().anyMatch(m -> m.getCpuCoreSeconds() == 0.0));
    }

    @Test
    void filter_warmupMetricsRecorded() throws InterruptedException {
        // Filter records even during warmup period
        invoke("POST", "/api/process");
        assertFalse(store.getAll().isEmpty());
    }
}
