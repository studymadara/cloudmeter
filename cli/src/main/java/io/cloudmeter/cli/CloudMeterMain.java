package io.cloudmeter.cli;

/**
 * Entry point for the CloudMeter CLI fat JAR.
 *
 * This thin wrapper exists so that {@link CloudMeterCli} (which contains all
 * testable business logic) can be tested without triggering {@code System.exit()}.
 * JaCoCo excludes this class from coverage checks — see {@code cli/build.gradle}.
 */
public final class CloudMeterMain {

    private CloudMeterMain() {}

    public static void main(String[] args) {
        System.exit(CloudMeterCli.run(args, System.out, System.err, new ReportCommand()));
    }
}
