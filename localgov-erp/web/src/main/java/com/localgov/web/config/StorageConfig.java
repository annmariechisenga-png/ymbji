package com.localgov.web.config;

import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Paths;

@Component
public class StorageConfig {
    @PostConstruct
    public void init() throws Exception {
        Files.createDirectories(Paths.get("storage/tenant_logos/"));
    }
}
