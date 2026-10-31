package cloud.changlu.agentforge.service.modules.upload.config;

import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.servlet.MultipartConfigElement;

/** Exposes locally uploaded files through the configured visit path. */
@Configuration
public class UploadWebMvcConfiguration implements WebMvcConfigurer {

    private final LocalUploadProperties properties;

    public UploadWebMvcConfiguration(LocalUploadProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(properties.getResourcePattern())
                .addResourceLocations(properties.getStorageRoot().toUri().toString());
    }

    @Bean
    public MultipartConfigElement multipartConfigElement() {
        DataSize limit = DataSize.ofBytes(properties.getMaxFileSize());
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(limit);
        factory.setMaxRequestSize(limit);
        return factory.createMultipartConfig();
    }
}
