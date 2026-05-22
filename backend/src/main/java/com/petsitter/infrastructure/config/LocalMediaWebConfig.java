package com.petsitter.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

@Configuration
@Profile("local")
public class LocalMediaWebConfig implements WebMvcConfigurer {

    @Value("${app.storage.local.dir:/tmp/cat_sitter_media}")
    private String localStorageDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String absolutePath = Paths.get(localStorageDir).toAbsolutePath().toUri().toString();
        registry.addResourceHandler("/local-media/**")
                .addResourceLocations(absolutePath);
    }
}
