package com.artembelikov.listview.capture;

import static org.assertj.core.api.Assertions.assertThat;

import com.artembelikov.listview.config.ListViewProperties;
import com.artembelikov.listview.dto.RemoteConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteConfigServiceTest {

    private String originalUserHome;
    private ObjectMapper objectMapper;

    @BeforeEach
    void saveUserHome() {
        originalUserHome = System.getProperty("user.home");
        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty("user.home", originalUserHome);
    }

    private RemoteConfigService serviceWithRemoteUrl(String remoteConfigUrl) {
        ListViewProperties properties = new ListViewProperties();
        properties.setRemoteConfigUrl(remoteConfigUrl);
        return new RemoteConfigService(properties, objectMapper);
    }

    @Test
    void createsTemplateFileWhenLocalFileIsMissing(@TempDir Path home) throws IOException {
        System.setProperty("user.home", home.toString());
        RemoteConfigService service = serviceWithRemoteUrl(null);

        RemoteConfig config = service.load();

        assertThat(config.schemas()).isEmpty();
        assertThat(config.dashboards()).isEmpty();
        Path created = home.resolve(".wireglass").resolve("dashboards.json");
        assertThat(created).exists();
        assertThat(Files.readString(created)).contains("\"version\":1");
    }

    @Test
    void loadsLocalFileTaggedOriginLocal(@TempDir Path home) throws IOException {
        System.setProperty("user.home", home.toString());
        Files.createDirectories(home.resolve(".wireglass"));
        Files.writeString(home.resolve(".wireglass").resolve("dashboards.json"), """
                {
                  "version": 1,
                  "schemas": [
                    {"id":"s1","name":"Local schema","pattern":"/","target":"response","schema":{"type":"object"}}
                  ],
                  "dashboards": [
                    {"id":"d1","name":"Local dash","system":"grafana","scope":"global","urlTemplate":"http://x/d"}
                  ]
                }
                """);
        RemoteConfigService service = serviceWithRemoteUrl(null);

        RemoteConfig config = service.load();

        assertThat(config.schemas()).hasSize(1);
        assertThat(config.schemas().get(0).id()).isEqualTo("s1");
        assertThat(config.schemas().get(0).origin()).isEqualTo("local");
        assertThat(config.dashboards()).hasSize(1);
        assertThat(config.dashboards().get(0).id()).isEqualTo("d1");
        assertThat(config.dashboards().get(0).origin()).isEqualTo("local");
    }

    @Test
    void localWinsOnIdCollisionWithRemote(@TempDir Path home) throws IOException {
        System.setProperty("user.home", home.toString());
        Files.createDirectories(home.resolve(".wireglass"));
        Files.writeString(home.resolve(".wireglass").resolve("dashboards.json"), """
                {
                  "version": 1,
                  "schemas": [
                    {"id":"shared-schema","name":"Local version of shared schema","pattern":"/","target":"response","schema":{"type":"object","required":["fromLocal"]}}
                  ],
                  "dashboards": [
                    {"id":"shared-dashboard","name":"Local version of shared dashboard","system":"grafana","scope":"global","urlTemplate":"http://local.example/dash"}
                  ]
                }
                """);
        RemoteConfigService service =
                serviceWithRemoteUrl("classpath:/remote-config/merge-test-remote.json");

        RemoteConfig config = service.load();

        assertThat(config.schemas()).hasSize(2);
        RemoteConfig.RemoteSchemaRule shared = config.schemas().stream()
                .filter(r -> r.id().equals("shared-schema")).findFirst().orElseThrow();
        assertThat(shared.name()).isEqualTo("Local version of shared schema");
        assertThat(shared.origin()).isEqualTo("local");
        RemoteConfig.RemoteSchemaRule remoteOnly = config.schemas().stream()
                .filter(r -> r.id().equals("remote-only-schema")).findFirst().orElseThrow();
        assertThat(remoteOnly.origin()).isEqualTo("remote");

        assertThat(config.dashboards()).hasSize(2);
        RemoteConfig.RemoteDashboardLink sharedDash = config.dashboards().stream()
                .filter(l -> l.id().equals("shared-dashboard")).findFirst().orElseThrow();
        assertThat(sharedDash.name()).isEqualTo("Local version of shared dashboard");
        assertThat(sharedDash.origin()).isEqualTo("local");
    }

    @Test
    void malformedLocalFileDoesNotBlockRemoteSource(@TempDir Path home) throws IOException {
        System.setProperty("user.home", home.toString());
        Files.createDirectories(home.resolve(".wireglass"));
        Files.writeString(home.resolve(".wireglass").resolve("dashboards.json"), "{not valid json");
        RemoteConfigService service =
                serviceWithRemoteUrl("classpath:/remote-config/merge-test-remote.json");

        RemoteConfig config = service.load();

        assertThat(config.schemas()).extracting(RemoteConfig.RemoteSchemaRule::id)
                .containsExactlyInAnyOrder("shared-schema", "remote-only-schema");
        assertThat(config.schemas()).allMatch(r -> r.origin().equals("remote"));
    }

    @Test
    void unreachableRemoteUrlDoesNotBlockLocalSource(@TempDir Path home) throws IOException {
        System.setProperty("user.home", home.toString());
        Files.createDirectories(home.resolve(".wireglass"));
        Files.writeString(home.resolve(".wireglass").resolve("dashboards.json"), """
                {
                  "version": 1,
                  "schemas": [
                    {"id":"s1","name":"Local schema","pattern":"/","target":"response","schema":{"type":"object"}}
                  ],
                  "dashboards": []
                }
                """);
        RemoteConfigService service = serviceWithRemoteUrl("http://127.0.0.1:1/does-not-exist");

        RemoteConfig config = service.load();

        assertThat(config.schemas()).hasSize(1);
        assertThat(config.schemas().get(0).origin()).isEqualTo("local");
    }
}
