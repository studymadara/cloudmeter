package io.cloudmeter.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class RouteNormalizerTest {

    @Test
    void null_returnsRoot() {
        assertEquals("/", RouteNormalizer.normalize(null));
    }

    @Test
    void empty_returnsRoot() {
        assertEquals("/", RouteNormalizer.normalize(""));
    }

    @Test
    void rootPath_unchanged() {
        assertEquals("/", RouteNormalizer.normalize("/"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "/api/users/123,                    /api/users/{id}",
        "/api/orders/9999999,               /api/orders/{id}",
        "/v1/items/0,                       /v1/items/{id}",
        "/api/users/550e8400-e29b-41d4-a716-446655440000, /api/users/{uuid}",
        "/api/files/abc123de,               /api/files/{slug}",
        "/reports/a1b2c3d4e5f6,             /reports/{slug}",
        "/api/health,                       /api/health",
        "/api/users,                        /api/users",
        "/,                                 /",
    })
    void normalization(String input, String expected) {
        assertEquals(expected.trim(), RouteNormalizer.normalize(input.trim()));
    }

    @Test
    void queryStringStripped() {
        assertEquals("/api/users/{id}", RouteNormalizer.normalize("/api/users/42?verbose=true&page=2"));
    }

    @Test
    void multipleSegments_mixedTypes() {
        assertEquals("/api/v2/users/{id}/posts/{id}",
                RouteNormalizer.normalize("/api/v2/users/123/posts/456"));
    }

    @Test
    void uuid_caseInsensitive() {
        assertEquals("/resource/{uuid}",
                RouteNormalizer.normalize("/resource/AABBCCDD-1122-3344-5566-778899AABBCC"));
    }

    @Test
    void pureAlphaSegment_notTreatedAsSlug() {
        // "cloudmeter" is all letters — should NOT become {slug}
        assertEquals("/cloudmeter/health", RouteNormalizer.normalize("/cloudmeter/health"));
    }

    @Test
    void shortMixedAlphanumeric_notTreatedAsSlug() {
        // < 8 chars, so not a slug even if mixed
        assertEquals("/a1b2c3/data", RouteNormalizer.normalize("/a1b2c3/data"));
    }

    @Test
    void longMixedAlphanumeric_treatedAsSlug() {
        // >= 8 chars, has digit + letter
        assertEquals("/tokens/{slug}", RouteNormalizer.normalize("/tokens/abc123de"));
    }

    @Test
    void pureDigits_becomesId() {
        assertEquals("/order/{id}/line/{id}", RouteNormalizer.normalize("/order/1001/line/2"));
    }

    @Test
    void leadingSlashPreserved() {
        assertEquals("/api/status", RouteNormalizer.normalize("/api/status"));
    }

    @Test
    void noLeadingSlash_handledGracefully() {
        // path.split("/", -1) on "api/users" → ["api","users"] — both non-empty, no leading /
        assertEquals("/api/users", RouteNormalizer.normalize("api/users"));
    }
}
