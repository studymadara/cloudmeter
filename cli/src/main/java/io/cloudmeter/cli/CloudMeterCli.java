package io.cloudmeter.cli;

import io.cloudmeter.costengine.ProjectionConfig;

import java.io.PrintStream;

/**
 * CloudMeter command-line interface entry point.
 *
 * Usage:
 * <pre>
 *   java -jar cloudmeter-cli.jar report [options]
 *   java -jar cloudmeter-cli.jar report --format json [options]
 *   java -jar cloudmeter-cli.jar report --host localhost --port 7777 [options]
 * </pre>
 *
 * Options (all optional):
 * <pre>
 *   --host HOST           Dashboard host        (default: 127.0.0.1)
 *   --port PORT           Dashboard port        (default: 7777)
 *   --format terminal|json Output format        (default: terminal)
 *   --provider AWS|GCP|AZURE Cloud provider     (default: AWS)
 *   --region REGION       Cloud region          (default: us-east-1)
 *   --users N             Target user count     (default: 1000)
 *   --rpu N               Requests/user/sec     (default: 1.0)
 *   --budget N            Monthly budget (USD)  (default: 0 = disabled)
 * </pre>
 *
 * Exit codes:
 *   0  — success, no budget thresholds exceeded
 *   1  — one or more endpoints exceed the configured budget threshold
 *   2  — usage error or connection failure
 */
public final class CloudMeterCli {

    public static final int EXIT_OK      = 0;
    public static final int EXIT_BUDGET  = 1;
    public static final int EXIT_ERROR   = 2;

    private CloudMeterCli() {}

    /**
     * Testable entry point — returns an exit code instead of calling {@code System.exit}.
     * The fat-JAR entry point lives in {@link CloudMeterMain}.
     */
    public static int run(String[] args, PrintStream out, PrintStream err, ReportCommand cmd) {
        if (args == null || args.length == 0) {
            printUsage(err);
            return EXIT_ERROR;
        }

        String subcommand = args[0];
        if ("attach".equalsIgnoreCase(subcommand)) {
            return runAttach(args, out, err);
        }
        if (!"report".equalsIgnoreCase(subcommand)) {
            err.println("[cloudmeter] Unknown subcommand: " + subcommand);
            printUsage(err);
            return EXIT_ERROR;
        }

        // Parse flags
        String host   = "127.0.0.1";
        int    port   = 7777;
        String format = "terminal";
        String cliArgStr = "";

        StringBuilder argBuilder = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            String flag = args[i];
            String next = (i + 1 < args.length) ? args[i + 1] : null;
            if ("--host".equals(flag) && next != null) {
                host = next; i++;
            } else if ("--port".equals(flag) && next != null) {
                port = parseIntFlag(next, port, err);
                i++;
            } else if ("--format".equals(flag) && next != null) {
                format = next; i++;
            } else if ("--provider".equals(flag) && next != null) {
                argBuilder.append("provider=").append(next).append(","); i++;
            } else if ("--region".equals(flag) && next != null) {
                argBuilder.append("region=").append(next).append(","); i++;
            } else if ("--users".equals(flag) && next != null) {
                argBuilder.append("targetUsers=").append(next).append(","); i++;
            } else if ("--rpu".equals(flag) && next != null) {
                argBuilder.append("rpu=").append(next).append(","); i++;
            } else if ("--budget".equals(flag) && next != null) {
                argBuilder.append("budget=").append(next).append(","); i++;
            } else if (!flag.startsWith("--")) {
                err.println("[cloudmeter] Unrecognised flag: " + flag);
                return EXIT_ERROR;
            }
        }
        cliArgStr = argBuilder.toString();

        if (!"terminal".equalsIgnoreCase(format) && !"json".equalsIgnoreCase(format)) {
            err.println("[cloudmeter] Unknown format: " + format + ". Use 'terminal' or 'json'.");
            return EXIT_ERROR;
        }

        CliArgs parsed        = CliArgs.parse(cliArgStr);
        ProjectionConfig cfg  = parsed.toProjectionConfig();

        boolean exceeded = cmd.run(host, port, format, cfg, out);
        return exceeded ? EXIT_BUDGET : EXIT_OK;
    }

    private static int runAttach(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 2) {
            err.println("[cloudmeter] Usage: cloudmeter attach <pid> --agent-jar <path> [agent-args]");
            return EXIT_ERROR;
        }
        String pid = args[1];
        String agentJar = null;
        StringBuilder agentArgsBuilder = new StringBuilder();

        for (int i = 2; i < args.length; i++) {
            String flag = args[i];
            String next = (i + 1 < args.length) ? args[i + 1] : null;
            if ("--agent-jar".equals(flag) && next != null) {
                agentJar = next; i++;
            } else if ("--provider".equals(flag) && next != null) {
                agentArgsBuilder.append("provider=").append(next).append(","); i++;
            } else if ("--region".equals(flag) && next != null) {
                agentArgsBuilder.append("region=").append(next).append(","); i++;
            } else if ("--users".equals(flag) && next != null) {
                agentArgsBuilder.append("targetUsers=").append(next).append(","); i++;
            } else if ("--rpu".equals(flag) && next != null) {
                agentArgsBuilder.append("rpu=").append(next).append(","); i++;
            } else if ("--budget".equals(flag) && next != null) {
                agentArgsBuilder.append("budget=").append(next).append(","); i++;
            } else if ("--port".equals(flag) && next != null) {
                agentArgsBuilder.append("port=").append(next).append(","); i++;
            }
        }

        if (agentJar == null) {
            err.println("[cloudmeter] --agent-jar <path> is required for attach");
            return EXIT_ERROR;
        }

        boolean ok = AttachCommand.attach(pid, agentJar, agentArgsBuilder.toString(), out, err);
        return ok ? EXIT_OK : EXIT_ERROR;
    }

    private static int parseIntFlag(String val, int def, PrintStream err) {
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            err.println("[cloudmeter] Invalid integer value: " + val + " — using default " + def);
            return def;
        }
    }

    private static void printUsage(PrintStream out) {
        out.println("Usage: cloudmeter <subcommand> [options]");
        out.println();
        out.println("Subcommands:");
        out.println("  report   Fetch and display cost projections from a running dashboard");
        out.println("  attach   Attach CloudMeter to a running JVM without a restart");
        out.println();
        out.println("report options:");
        out.println("  --host HOST           Dashboard host        (default: 127.0.0.1)");
        out.println("  --port PORT           Dashboard port        (default: 7777)");
        out.println("  --format terminal|json Output format       (default: terminal)");
        out.println("  --provider AWS|GCP|AZURE                   (default: AWS)");
        out.println("  --region REGION                            (default: us-east-1)");
        out.println("  --users N             Target user count     (default: 1000)");
        out.println("  --rpu N               Requests/user/sec     (default: 1.0)");
        out.println("  --budget N            Monthly budget (USD)  (default: 0 = disabled)");
        out.println();
        out.println("attach options:");
        out.println("  <pid>                 Target JVM process ID (required)");
        out.println("  --agent-jar <path>    Path to cloudmeter-agent.jar (required)");
        out.println("  --provider AWS|GCP|AZURE");
        out.println("  --region REGION");
        out.println("  --users N");
        out.println("  --rpu N");
        out.println("  --budget N");
        out.println("  --port N              Dashboard port on target JVM (default: 7777)");
        out.println();
        out.println("Exit codes: 0 = ok, 1 = budget exceeded, 2 = error");
    }
}
