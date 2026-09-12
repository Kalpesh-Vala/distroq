package com.distroq.dashboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serves the built dashboard bundle, if there is one.
 *
 * <p>Two locations, checked in order. {@code classpath:/static/dashboard/} is the fat-jar case: a
 * bundle copied into {@code src/main/resources/static/dashboard} before {@code mvn package} ships
 * inside the artifact. The configured {@code distroq.dashboard.static-path} is the deployment
 * case: a directory next to the jar, updated without rebuilding it. Neither is required, and with
 * neither present the API still works and the frontend is simply run from {@code npm run dev}.
 *
 * <p>Maven is deliberately not made to run npm. The frontend has to build on its own —
 * {@code npm ci && npm run build} — and wiring a Node download into {@code mvnw package} would
 * make the backend build depend on a network fetch and a toolchain that has nothing to do with
 * Java. The price is one documented copy step; see README.md.
 *
 * <p>Only {@code /dashboard/**} is mapped, which is why the Vite base is {@code /dashboard/}. The
 * API keeps every path it had, {@code /actuator} is untouched, and {@code /} is a redirect rather
 * than a handler so that nothing here can shadow a future root endpoint.
 *
 * <p>The bundle is served unauthenticated and contains no data and no credentials — it is HTML,
 * CSS and JavaScript that knows how to ask for data it cannot see. Everything it renders comes
 * from {@code /api/dashboard}, which is authenticated. Path traversal out of the configured
 * directory is refused by {@link PathResourceResolver}, which resolves candidates and then checks
 * they are still under the location.
 */
@Configuration(proxyBeanMethods = false)
public class DashboardWebConfiguration implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(DashboardWebConfiguration.class);

    static final String CLASSPATH_LOCATION = "classpath:/static/dashboard/";

    private final String staticPath;

    public DashboardWebConfiguration(DashboardProperties properties) {
        this.staticPath = properties.staticPath();
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/dashboard/");
        registry.addRedirectViewController("/dashboard", "/dashboard/");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String[] locations = locations();
        if (locations.length == 1) {
            log.info("No built dashboard bundle found; /dashboard/ will report how to build one. "
                    + "Run 'npm run build' in dashboard/ and set distroq.dashboard.static-path.");
        }
        registry.addResourceHandler("/dashboard/**")
                .addResourceLocations(locations)
                .resourceChain(true)
                .addResolver(new SpaResolver());
    }

    private String[] locations() {
        Path directory = staticPath == null || staticPath.isBlank() ? null : Path.of(staticPath);
        if (directory != null && Files.isRegularFile(directory.resolve("index.html"))) {
            // toUri() keeps the trailing separator Spring needs to treat this as a directory
            return new String[] {directory.toUri().toString(), CLASSPATH_LOCATION};
        }
        return new String[] {CLASSPATH_LOCATION};
    }

    /**
     * Client-side routes are not files.
     *
     * <p>The dashboard's own navigation lives in the URL, so {@code /dashboard/queues} must return
     * the same {@code index.html} that {@code /dashboard/} does rather than a 404 — otherwise a
     * reload or a bookmarked link breaks.
     *
     * <p>Only paths with no file extension fall back. A request for a missing
     * {@code assets/index-abc123.js} must stay a 404: answering it with HTML would produce a MIME
     * type error in the browser and hide a broken deployment behind a page that half-loads.
     */
    private static final class SpaResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource requested = super.getResource(resourcePath, location);
            if (requested != null || resourcePath.contains(".")) {
                return requested;
            }
            return super.getResource("index.html", location);
        }
    }
}
