package io.cloudmeter.cli;

import io.cloudmeter.costengine.EndpointCostProjection;
import io.cloudmeter.costengine.ProjectionConfig;
import io.cloudmeter.reporter.JsonReporter;
import io.cloudmeter.reporter.TerminalReporter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * Fetches projections from a running CloudMeter dashboard and renders them
 * in terminal or JSON format.
 *
 * Connection to the dashboard is injectable via {@link #ReportCommand(UrlSupplier)}
 * so that tests can provide a custom response without network access.
 */
public final class ReportCommand {

    /** Abstraction over the HTTP fetch so tests can substitute a stub. */
    public interface UrlSupplier {
        String fetch(String url) throws IOException;
    }

    private final UrlSupplier fetcher;

    /** Creates a command that talks to a real HTTP server. */
    public ReportCommand() {
        this(ReportCommand::defaultFetch);
    }

    /** Creates a command with a custom fetcher (for testing). */
    public ReportCommand(UrlSupplier fetcher) {
        if (fetcher == null) throw new NullPointerException("fetcher");
        this.fetcher = fetcher;
    }

    /**
     * Executes the report command.
     *
     * @param host        dashboard host (e.g. "127.0.0.1")
     * @param port        dashboard port (e.g. 7777)
     * @param format      "terminal" or "json"
     * @param config      projection config used to re-render the fetched data
     * @param out         destination stream
     * @return {@code true} if any endpoint exceeds the budget threshold
     */
    public boolean run(String host, int port, String format,
                       ProjectionConfig config, PrintStream out) {
        String url = "http://" + host + ":" + port + "/api/projections";
        String json;
        try {
            json = fetcher.fetch(url);
        } catch (IOException e) {
            out.println("[cloudmeter] ERROR: could not connect to dashboard at " + url);
            out.println("             Is the agent running? Start with -javaagent:cloudmeter-agent.jar");
            return false;
        }

        // Parse the JSON minimally — extract route names and costs for re-rendering.
        // A full JSON parser is overkill for the CLI; we reuse JsonReporter's output as-is
        // and optionally render via TerminalReporter from the raw projections list.
        List<EndpointCostProjection> projections = parseProjections(json, config);

        if ("json".equalsIgnoreCase(format)) {
            return JsonReporter.print(projections, config, out);
        } else {
            TerminalReporter.print(projections, config, out);
            return projections.stream().anyMatch(EndpointCostProjection::isExceedsBudget);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Minimal JSON extraction from the dashboard's projection response.
     *
     * Rather than bundling a full parser, we re-project from the raw JSON using
     * the cost engine — the dashboard already runs CostProjector, and the CLI
     * fetches that output.  For a clean pipeline, the CLI re-renders from the
     * live store; here we parse the key fields we need for display.
     *
     * Returns an empty list on any parse error (fail-safe, ADR-010).
     */
    static List<EndpointCostProjection> parseProjections(String json, ProjectionConfig config) {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        return JsonProjectionParser.parse(json, config);
    }

    static String defaultFetch(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(10_000);
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("HTTP " + code + " from " + urlStr);
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
