package io.cloudmeter.sidecar;

/**
 * Parses command-line arguments for the CloudMeter sidecar process.
 *
 * Supports both {@code --key value} and {@code --key=value} forms.
 */
public final class SidecarArgs {

    private String provider                  = "AWS";
    private String region                    = "us-east-1";
    private int    targetUsers               = 1000;
    private double budgetUsd                 = 0.0;
    private int    dashboardPort             = 7777;
    private int    ingestPort                = 7778;
    private double requestsPerUserPerSecond  = 1.0;

    private SidecarArgs() {}

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getProvider()                  { return provider; }
    public String getRegion()                    { return region; }
    public int    getTargetUsers()               { return targetUsers; }
    public double getBudgetUsd()                 { return budgetUsd; }
    public int    getDashboardPort()             { return dashboardPort; }
    public int    getIngestPort()                { return ingestPort; }
    public double getRequestsPerUserPerSecond()  { return requestsPerUserPerSecond; }

    // ── Parser ────────────────────────────────────────────────────────────────

    /**
     * Parse args array into a {@link SidecarArgs} instance.
     *
     * @param args command-line arguments
     * @return parsed args with defaults applied for missing keys
     * @throws IllegalArgumentException for unknown flags or bad values
     */
    public static SidecarArgs parse(String[] args) {
        SidecarArgs result = new SidecarArgs();
        if (args == null || args.length == 0) {
            return result;
        }

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + arg);
            }

            String key;
            String value;

            int eqIdx = arg.indexOf('=');
            if (eqIdx >= 0) {
                key   = arg.substring(2, eqIdx);
                value = arg.substring(eqIdx + 1);
            } else {
                key = arg.substring(2);
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing value for flag: " + arg);
                }
                value = args[++i];
            }

            switch (key) {
                case "provider":
                    result.provider = value;
                    break;
                case "region":
                    result.region = value;
                    break;
                case "target-users":
                    result.targetUsers = Integer.parseInt(value);
                    break;
                case "budget-usd":
                    result.budgetUsd = Double.parseDouble(value);
                    break;
                case "dashboard-port":
                    result.dashboardPort = Integer.parseInt(value);
                    break;
                case "ingest-port":
                    result.ingestPort = Integer.parseInt(value);
                    break;
                case "requests-per-user-per-second":
                    result.requestsPerUserPerSecond = Double.parseDouble(value);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown flag: --" + key);
            }
        }

        return result;
    }
}
