package com.artembelikov.listview.capture;

import com.artembelikov.listview.config.ListViewProperties;
import com.artembelikov.listview.dto.RemoteConfig;
import com.artembelikov.listview.dto.RemoteConfig.RemoteDashboardLink;
import com.artembelikov.listview.dto.RemoteConfig.RemoteSchemaRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Loads read-only JSON Schema validation rules and dashboard links, merging two sources: a local
 * file at {@code ~/.wireglass/dashboards.json} (auto-created empty on first read) and the
 * server-hosted config named by {@code app.listview.remote-config-url}, as described in
 * {@code docs/server-config-format.md}. Either source may be absent; a failure in one never
 * blocks the other. On id collisions between the two, the local file wins.
 */
@Service
public class RemoteConfigService {

    private static final Logger LOG = LoggerFactory.getLogger(RemoteConfigService.class);
    private static final RemoteConfig EMPTY = new RemoteConfig(1, List.of(), List.of());
    private static final String LOCAL_TEMPLATE = """
            {
              "version": 1,
              "schemas": [],
              "dashboards": []
            }
            """;

    private final ListViewProperties properties;
    private final ObjectMapper objectMapper;

    @Autowired
    public RemoteConfigService(ListViewProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public RemoteConfig load() {
        RemoteConfig local = loadLocalFile();
        RemoteConfig remote = loadRemote();
        return new RemoteConfig(1,
                mergeById(local.schemas(), remote.schemas(), RemoteSchemaRule::id),
                mergeById(local.dashboards(), remote.dashboards(), RemoteDashboardLink::id));
    }

    private RemoteConfig loadLocalFile() {
        Path path = localConfigPath();
        try {
            ensureLocalFileExists(path);
            try (InputStream in = Files.newInputStream(path)) {
                return tagOrigin(objectMapper.readValue(in, RemoteConfig.class), "local");
            }
        } catch (Exception e) {
            LOG.warn("Failed to load local dashboard config from {}", path, e);
            return EMPTY;
        }
    }

    private Path localConfigPath() {
        return Path.of(System.getProperty("user.home"), ".wireglass", "dashboards.json");
    }

    private void ensureLocalFileExists(Path path) throws IOException {
        if (Files.exists(path)) {
            return;
        }
        Files.createDirectories(path.getParent());
        Files.writeString(path, LOCAL_TEMPLATE);
    }

    private RemoteConfig loadRemote() {
        String url = properties.getRemoteConfigUrl();
        if (url == null || url.isBlank()) {
            return EMPTY;
        }
        try {
            RemoteConfig config = fetch(url.trim());
            if (config.version() != 1) {
                throw new IllegalArgumentException(
                        "Unsupported server config version: " + config.version() + " (expected 1)");
            }
            return tagOrigin(config, "remote");
        } catch (Exception e) {
            LOG.warn("Failed to load remote dashboard config from {}", url, e);
            return EMPTY;
        }
    }

    private RemoteConfig tagOrigin(RemoteConfig config, String origin) {
        return new RemoteConfig(config.version(),
                config.schemas().stream().map(r -> r.withOrigin(origin)).toList(),
                config.dashboards().stream().map(l -> l.withOrigin(origin)).toList());
    }

    private <T> List<T> mergeById(List<T> local, List<T> remote, Function<T, String> idOf) {
        Map<String, T> byId = new LinkedHashMap<>();
        local.forEach(item -> byId.put(idOf.apply(item), item));
        remote.forEach(item -> byId.putIfAbsent(idOf.apply(item), item));
        return List.copyOf(byId.values());
    }

    private RemoteConfig fetch(String url) throws IOException {
        if (url.startsWith("classpath:")) {
            return readClasspath(url);
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return readHttp(url);
        }
        throw new IllegalArgumentException(
                "Unsupported app.listview.remote-config-url scheme: " + url);
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
