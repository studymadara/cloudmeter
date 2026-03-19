package io.cloudmeter.smoketestjaxrs;

import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;

import java.io.File;

/**
 * Embedded Tomcat 9.x with Jersey 2.x JAX-RS — no Spring Framework.
 * Verifies that the CloudMeter agent intercepts JAX-RS requests via the underlying
 * HttpServlet.service() call that Jersey makes for every request.
 *
 * Runs on port 8083 to avoid conflicts with:
 *   smoke-test (8080), smoke-test-sb2 (8081), smoke-test-servlet (8082).
 *
 * Usage:
 *   java -javaagent:agent/build/libs/agent-0.1.0.jar=provider=AWS,targetUsers=5000 \
 *        -jar smoke-test-jaxrs/build/libs/smoke-test-jaxrs-app.jar
 */
public class Main {

    public static void main(String[] args) throws Exception {
        Tomcat tomcat = new Tomcat();
        tomcat.setPort(8083);
        tomcat.getConnector(); // trigger connector creation

        String baseDir = System.getProperty("java.io.tmpdir") + File.separator + "cloudmeter-jaxrs-smoke";
        tomcat.setBaseDir(baseDir);

        Context ctx = tomcat.addContext("", new File(".").getAbsolutePath());

        // Register Jersey as a standard servlet
        Wrapper jerseyServlet = Tomcat.addServlet(ctx, "jersey",
                "org.glassfish.jersey.servlet.ServletContainer");
        jerseyServlet.addInitParameter(
                "javax.ws.rs.Application",
                "io.cloudmeter.smoketestjaxrs.SmokeApplication");
        ctx.addServletMappingDecoded("/*", "jersey");

        tomcat.start();
        System.out.println("[smoke-test-jaxrs] Tomcat + Jersey started on port 8083");
        System.out.println("[smoke-test-jaxrs] Endpoints:");
        System.out.println("[smoke-test-jaxrs]   GET  http://localhost:8083/api/health");
        System.out.println("[smoke-test-jaxrs]   GET  http://localhost:8083/api/users/{id}");
        System.out.println("[smoke-test-jaxrs]   POST http://localhost:8083/api/process");
        tomcat.getServer().await();
    }
}
