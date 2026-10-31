package cloud.changlu.agentforge.service.config;

import cloud.changlu.agentforge.service.env.EnvironmentContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

/**
 * Serves the built frontend from an external directory so the all-in-one image can ship the UI
 * without repackaging it into the jar.
 *
 * <p>When the directory does not contain an {@code index.html}, this configuration backs off and
 * Spring Boot's default classpath static resources remain available for local development.
 *
 * @author changlu
 * @since 2026-10-29
 */
@Configuration
public class WebStaticConfiguration implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebStaticConfiguration.class);

    private final EnvironmentContext environmentContext;

    public WebStaticConfiguration(EnvironmentContext environmentContext) {
        this.environmentContext = environmentContext;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = environmentContext.getWebStaticLocation();
        if (!StringUtils.hasText(location)) {
            return;
        }
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        File staticRoot = toFile(location);
        if (staticRoot == null || !new File(staticRoot, "index.html").isFile()) {
            log.info(
                    "Frontend static directory is absent, skip external static hosting: {}",
                    location);
            return;
        }
        log.info("Serving frontend static resources from {}", staticRoot.getAbsolutePath());
        registry.addResourceHandler("/assets/**").addResourceLocations(location + "assets/");
        registry.addResourceHandler("/index.html", "/favicon.ico", "/logo.png")
                .addResourceLocations(location);
    }

    private File toFile(String location) {
        if (!location.startsWith("file:")) {
            return null;
        }
        try {
            return new File(location.substring("file:".length()));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
