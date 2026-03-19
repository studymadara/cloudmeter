package io.cloudmeter.smoketestservlet;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;

import java.io.File;

/**
 * Embedded Tomcat 9.x — no Spring, raw javax.servlet only.
 * Verifies CloudMeter agent intercepts HttpServlet.service() directly.
 *
 * Runs on port 8082 to avoid conflicts with smoke-test (8080) and
 * smoke-test-sb2 (8081).
 */
public class Main {

    public static void main(String[] args) throws Exception {
        Tomcat tomcat = new Tomcat();
        tomcat.setPort(8082);
        tomcat.getConnector(); // trigger connector creation

        // Tomcat needs a base dir for work files
        String baseDir = System.getProperty("java.io.tmpdir") + File.separator + "cloudmeter-servlet-smoke";
        tomcat.setBaseDir(baseDir);

        Context ctx = tomcat.addContext("", new File(".").getAbsolutePath());

        // Register servlets
        Tomcat.addServlet(ctx, "health", new HealthServlet());
        ctx.addServletMappingDecoded("/api/health", "health");

        Tomcat.addServlet(ctx, "users", new UserServlet());
        ctx.addServletMappingDecoded("/api/users/*", "users");

        Tomcat.addServlet(ctx, "process", new ProcessServlet());
        ctx.addServletMappingDecoded("/api/process", "process");

        tomcat.start();
        System.out.println("[smoke-test-servlet] Tomcat started on port 8082");
        tomcat.getServer().await();
    }
}
