package io.cloudmeter.agent;

import java.util.regex.Pattern;

/**
 * Heuristic HTTP path → route template normalizer.
 *
 * Used as a fallback when a framework-provided template (e.g. Spring MVC's
 * {@code HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE}) is not available.
 *
 * Replacement rules (applied per path segment, in priority order):
 * <ol>
 *   <li>UUID (8-4-4-4-12 hex) → {@code {uuid}}</li>
 *   <li>Pure digits            → {@code {id}}</li>
 *   <li>Mixed alphanumeric, ≥ 8 chars, contains at least one digit and one letter → {@code {slug}}</li>
 *   <li>Anything else          → kept as-is (route segment name)</li>
 * </ol>
 *
 * Query strings are stripped before normalization.
 */
final class RouteNormalizer {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final Pattern NUMERIC_PATTERN =
            Pattern.compile("\\d+");

    /**
     * Mixed alphanumeric slug: must contain at least one digit AND one letter, min 8 chars.
     * The lookaheads prevent pure-alphabetic words (route names) from being mistaken for slugs.
     */
    private static final Pattern SLUG_PATTERN =
            Pattern.compile("(?=.*[0-9])(?=.*[a-zA-Z])[a-zA-Z0-9]{8,}");

    private RouteNormalizer() {}

    /**
     * Normalizes a raw HTTP request path to a route template string.
     *
     * @param path raw URI path (may include query string, may be null)
     * @return normalized template, e.g. {@code /api/users/{id}/posts}
     */
    static String normalize(String path) {
        if (path == null || path.isEmpty()) return "/";

        // Strip query string
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);

        String[] parts = path.split("/", -1);
        StringBuilder sb = new StringBuilder();

        for (String part : parts) {
            if (part.isEmpty()) continue;
            sb.append('/');
            if (UUID_PATTERN.matcher(part).matches()) {
                sb.append("{uuid}");
            } else if (NUMERIC_PATTERN.matcher(part).matches()) {
                sb.append("{id}");
            } else if (SLUG_PATTERN.matcher(part).matches()) {
                sb.append("{slug}");
            } else {
                sb.append(part);
            }
        }

        return sb.length() == 0 ? "/" : sb.toString();
    }
}
