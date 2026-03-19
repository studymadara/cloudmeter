package io.cloudmeter.smoketestservlet;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/** GET /api/health — instant response, near-zero cost. */
public class HealthServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.setContentType("application/json");
        resp.setStatus(200);
        resp.getWriter().write("{\"status\":\"ok\"}");
    }
}
