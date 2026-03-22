package io.cloudmeter.smoketestjaxrs;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;
import java.util.Collections;
import java.util.Set;

/**
 * JAX-RS application that registers the smoke-test resource.
 * Jersey discovers this class via the {@code javax.ws.rs.Application} servlet init param.
 */
@ApplicationPath("/")
public class SmokeApplication extends Application {

    @Override
    public Set<Class<?>> getClasses() {
        return Collections.singleton(SmokeResource.class);
    }
}
