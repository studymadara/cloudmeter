package io.cloudmeter.costengine;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches up-to-date pricing data from the CloudMeter pricing JSON hosted in the
 * project repository, and applies it to {@link PricingCatalog} in-memory.
 *
 * This is triggered when the user passes {@code fetchPrices=true} as an agent arg.
 * If the fetch fails for any reason (no network, HTTP error, parse error), the
 * static embedded prices are used unchanged — the failure is logged but never thrown.
 *
 * The pricing JSON format is:
 * <pre>
 * {
 *   "pricingDate": "2025-01-01",
 *   "egressRatePerGib": { "AWS": 0.09, "GCP": 0.085, "AZURE": 0.087 },
 *   "instances": [
 *     {"name": "t3.nano", "provider": "AWS", "vcpus": 2, "memoryGb": 0.5, "hourlyUsd": 0.0052},
 *     ...
 *   ]
 * }
 * </pre>
 */
public final class LivePricingFetcher {

    static final String DEFAULT_PRICING_URL =
            "https://raw.githubusercontent.com/studymadara/cloudmeter/develop/pricing/cloudmeter-prices.json";

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS    = 10_000;

    private LivePricingFetcher() {}

    /**
     * Fetches pricing from {@link #DEFAULT_PRICING_URL}, parses it, and applies it
     * to {@link PricingCatalog}. Returns {@code true} on success, {@code false} on
     * any failure. Failures are logged to stderr but never thrown.
     */
    public static boolean fetchAndApply() {
        return fetchAndApply(DEFAULT_PRICING_URL);
    }

    /**
     * Same as {@link #fetchAndApply()} but with a configurable URL — used in tests
     * to point at a local fixture.
     */
    public static boolean fetchAndApply(String url) {
        try {
            String json = download(url);
            ParsedPricing parsed = parse(json);
            PricingCatalog.applyLivePricing(parsed.date, parsed.instances, parsed.egress);
            return true;
        } catch (Exception e) {
            System.err.println("[CloudMeter] Live pricing fetch failed (" + e.getMessage()
                    + ") — using embedded static prices from " + PricingCatalog.PRICING_DATE);
            return false;
        }
    }

    // ── HTTP download ──────────────────────────────────────────────────────────

    static String download(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/json");

        int status = conn.getResponseCode();
        if (status != 200) {
            throw new IOException("HTTP " + status + " from " + url);
        }

        try (InputStream is = conn.getInputStream()) {
            byte[] buf = new byte[131_072]; // 128 KiB — more than enough for the pricing file
            int total = 0, n;
            while ((n = is.read(buf, total, buf.length - total)) > 0) {
                total += n;
                if (total == buf.length) throw new IOException("Pricing response too large (>128 KiB)");
            }
            return new String(buf, 0, total, StandardCharsets.UTF_8);
        }
    }

    // ── JSON parser ────────────────────────────────────────────────────────────
    // Hand-rolled for our controlled format — avoids adding a JSON library dependency.

    static ParsedPricing parse(String json) {
        String date = extractString(json, "pricingDate");
        if (date == null || date.isEmpty()) date = LocalDate.now().toString();

        Map<CloudProvider, Double> egress = new EnumMap<>(CloudProvider.class);
        String egressBlock = extractBlock(json, "egressRatePerGib");
        if (egressBlock != null) {
            for (CloudProvider p : CloudProvider.values()) {
                Double rate = extractDouble(egressBlock, p.name());
                if (rate != null) egress.put(p, rate);
            }
        }

        Map<CloudProvider, List<InstanceType>> instances = new EnumMap<>(CloudProvider.class);
        for (CloudProvider p : CloudProvider.values()) {
            instances.put(p, new ArrayList<>());
        }

        // Parse each { ... } object in the "instances" array
        int arrayStart = json.indexOf("\"instances\"");
        if (arrayStart >= 0) {
            int bracketOpen = json.indexOf('[', arrayStart);
            int bracketClose = json.lastIndexOf(']');
            if (bracketOpen >= 0 && bracketClose > bracketOpen) {
                String arrayContent = json.substring(bracketOpen + 1, bracketClose);
                parseInstanceArray(arrayContent, instances);
            }
        }

        // Sort each provider's list by hourlyUsd ascending (same order as static catalog)
        for (List<InstanceType> list : instances.values()) {
            list.sort((a, b) -> Double.compare(a.getHourlyUsd(), b.getHourlyUsd()));
        }

        // Wrap each list as unmodifiable
        Map<CloudProvider, List<InstanceType>> unmod = new EnumMap<>(CloudProvider.class);
        for (Map.Entry<CloudProvider, List<InstanceType>> e : instances.entrySet()) {
            unmod.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
        }

        return new ParsedPricing(date, unmod, Collections.unmodifiableMap(egress));
    }

    private static void parseInstanceArray(String arrayContent,
                                           Map<CloudProvider, List<InstanceType>> out) {
        int pos = 0;
        while (pos < arrayContent.length()) {
            int objStart = arrayContent.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = arrayContent.indexOf('}', objStart);
            if (objEnd < 0) break;

            String obj = arrayContent.substring(objStart + 1, objEnd);
            String name     = extractString(obj, "name");
            String provider = extractString(obj, "provider");
            Integer vcpus   = extractInt(obj, "vcpus");
            Double memory   = extractDouble(obj, "memoryGb");
            Double hourly   = extractDouble(obj, "hourlyUsd");

            if (name != null && provider != null && vcpus != null
                    && memory != null && memory > 0 && Double.isFinite(memory)
                    && hourly != null && hourly > 0 && Double.isFinite(hourly)) {
                try {
                    CloudProvider cp = CloudProvider.valueOf(provider.toUpperCase());
                    out.get(cp).add(new InstanceType(name, cp, vcpus, memory, hourly));
                } catch (IllegalArgumentException ignored) {
                    // Unknown provider — skip
                }
            }
            pos = objEnd + 1;
        }
    }

    // ── Minimal JSON field extractors ──────────────────────────────────────────

    /** Extracts the value of {@code "key": "value"} (string value). */
    static String extractString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    /** Extracts the value of {@code "key": <number>}. */
    static Double extractDouble(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return null;
        int start = colon + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t'
                || json.charAt(start) == '\n' || json.charAt(start) == '\r')) start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end))
                || json.charAt(end) == '.' || json.charAt(end) == '-')) end++;
        if (end == start) return null;
        try {
            return Double.parseDouble(json.substring(start, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Extracts the value of {@code "key": <integer>}. */
    static Integer extractInt(String json, String key) {
        Double d = extractDouble(json, key);
        return d == null ? null : (int) Math.round(d);
    }

    /**
     * Extracts the content of a nested object {@code "key": { ... }}.
     * Returns everything between the first {@code {} and its matching {@code }}.
     */
    static String extractBlock(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int brace = json.indexOf('{', idx + search.length());
        if (brace < 0) return null;
        // Find matching closing brace
        int depth = 1, pos = brace + 1;
        while (pos < json.length() && depth > 0) {
            char c = json.charAt(pos++);
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return json.substring(brace + 1, pos - 1);
    }

    // ── Result container ───────────────────────────────────────────────────────

    static final class ParsedPricing {
        final String                              date;
        final Map<CloudProvider, List<InstanceType>> instances;
        final Map<CloudProvider, Double>             egress;

        ParsedPricing(String date,
                      Map<CloudProvider, List<InstanceType>> instances,
                      Map<CloudProvider, Double> egress) {
            this.date      = date;
            this.instances = instances;
            this.egress    = egress;
        }
    }
}
