package com.artembelikov.listview.capture;

import com.artembelikov.listview.config.ListViewProperties;
import com.artembelikov.listview.dto.RemoteConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Loads read-only JSON Schema validation rules and dashboard links from a server-hosted config
 * file, as described in {@code docs/server-config-format.md}. The source is either a classpath
 * resource (used by tests and for packaged defaults) or an {@code http(s)://} URL.
 */
@Service
public class RemoteConfigService {

    private static final Logger LOG = LoggerFactory.getLogger(RemoteConfigService.class);
    private static final RemoteConfig EMPTY = new RemoteConfig(1, List.of(), List.of());

    private final ListViewProperties properties;
    private final ObjectMapper objectMapper;

    @Autowired
    public RemoteConfigService(ListViewProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public RemoteConfig load() {
        String url = properties.getRemoteConfigUrl();
        if (url == null || url.isBlank()) {
            return EMPTY;
        }
        RemoteConfig config = fetch(url.trim());
        if (config.version() != 1) {
            throw new IllegalArgumentException(
                    "Unsupported server config version: " + config.version() + " (expected 1)");
        }
        return config;
    }

    private RemoteConfig fetch(String url) {
        try {
            if (url.startsWith("classpath:")) {
                return readClasspath(url);
            }
            if (url.startsWith("http://") || url.startsWith("https://")) {
                return readHttp(url);
            }
            throw new IllegalArgumentException(
                    "Unsupported app.listview.remote-config-url scheme: " + url);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load server config from " + url, e);
        }
    }

    private RemoteConfig readClasspath(String url) throws IOException {
        String path = url.substring("classpath:".length());
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream in = resource.getInputStream()) {
            return objectMapper.readValue(in, RemoteConfig.class);
        }
    }

    private RemoteConfig readHttp(String url) {
        LOG.debug("Fetching server config from {}", url);
        return RestClient.create(url).get().retrieve().body(RemoteConfig.class);
    }
}
