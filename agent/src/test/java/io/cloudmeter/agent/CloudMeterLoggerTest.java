package io.cloudmeter.agent;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

class CloudMeterLoggerTest {

    @Test
    void info_writesToStderr_withPrefix() {
        PrintStream original = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf));
        try {
            CloudMeterLogger.info("test message");
            String output = buf.toString();
            assertTrue(output.contains("[CloudMeter]"));
            assertTrue(output.contains("INFO"));
            assertTrue(output.contains("test message"));
        } finally {
            System.setErr(original);
        }
    }

    @Test
    void warn_writesToStderr_withPrefix() {
        PrintStream original = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf));
        try {
            CloudMeterLogger.warn("something went wrong");
            String output = buf.toString();
            assertTrue(output.contains("[CloudMeter]"));
            assertTrue(output.contains("WARN"));
            assertTrue(output.contains("something went wrong"));
        } finally {
            System.setErr(original);
        }
    }
}
