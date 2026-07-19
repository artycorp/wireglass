package com.wireglass.listview;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionFileIT {

    private static final String RUN_ID = "11111111-1111-1111-1111-111111111111";
    private static final String PACKET_ID = "22222222-2222-2222-2222-222222222222";

    private static final String SESSION_JSON = """
            {
              "version": 1,
              "exportedAt": "2026-07-20T10:00:00Z",
              "runs": [{
                "id": "%s",
                "source": "external",
                "state": "FINISHED",
                "startedAt": "2026-07-20T09:59:00Z",
                "finishedAt": "2026-07-20T09:59:30Z",
                "label": "https://example.test/api",
                "threads": 2,
                "iterations": 3,
                "capturedSamples": 1,
                "errorSamples": 0,
                "restored": false
              }],
              "packets": [{
                "id": "%s",
                "runId": "%s",
                "type": "HTTP",
                "timestamp": "2026-07-20T09:59:10Z",
                "method": "GET",
                "url": "https://example.test/api",
                "status": 200,
                "success": true
              }]
            }
            """.formatted(RUN_ID, PACKET_ID, RUN_ID);

    @Autowired
    TestRestTemplate rest;

    private String originalUserHome;
    private Path tempHome;

    @BeforeEach
    void isolateHome() throws Exception {
        originalUserHome = System.getProperty("user.home");
        tempHome = Files.createTempDirectory("wireglass-session-home");
        System.setProperty("user.home", tempHome.toString());
        rest.delete("/api/packets");
    }

    @AfterEach
    void restoreHome() {
        rest.delete("/api/packets");
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
    }

    private ResponseEntity<JsonNode> postSession(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/api/session/import", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
    }

    @Test
    void importedRunIsMarkedRestoredAndItsPacketsAreVisible() {
        ResponseEntity<JsonNode> imported = postSession(SESSION_JSON);

        assertThat(imported.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(imported.getBody().get("importedRuns").asInt()).isEqualTo(1);
        assertThat(imported.getBody().get("importedPackets").asInt()).isEqualTo(1);

        JsonNode runs = rest.getForObject("/api/runs", JsonNode.class);
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).get("id").asText()).isEqualTo(RUN_ID);
        assertThat(runs.get(0).get("restored").asBoolean())
                .as("a run loaded from a session file must be flagged restored")
                .isTrue();
        assertThat(runs.get(0).get("label").asText()).isEqualTo("https://example.test/api");

        JsonNode packets = rest.getForObject("/api/packets?runId=" + RUN_ID, JsonNode.class);
        assertThat(packets).hasSize(1);
        assertThat(packets.get(0).get("url").asText()).isEqualTo("https://example.test/api");
    }

    @Test
    void reimportingTheSameSessionAddsNothing() {
        postSession(SESSION_JSON);

        ResponseEntity<JsonNode> second = postSession(SESSION_JSON);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("importedPackets").asInt()).isZero();
        assertThat(second.getBody().get("skippedPackets").asInt()).isEqualTo(1);
        assertThat(rest.getForObject("/api/packets", JsonNode.class)).hasSize(1);
    }

    @Test
    void exportRoundTripsThroughImport() {
        postSession(SESSION_JSON);

        ResponseEntity<String> exported = rest.getForEntity("/api/session/export", String.class);
        assertThat(exported.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exported.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment", "wireglass-session-", ".json");

        rest.delete("/api/packets");
        assertThat(rest.getForObject("/api/runs", JsonNode.class)).isEmpty();

        ResponseEntity<JsonNode> reimported = postSession(exported.getBody());

        assertThat(reimported.getBody().get("importedPackets").asInt()).isEqualTo(1);
        JsonNode runs = rest.getForObject("/api/runs", JsonNode.class);
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).get("restored").asBoolean()).isTrue();
    }

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    @Test
    void aRestoredRunIsLabelledInTheRunSelector() {
        postSession(SESSION_JSON);

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium()
                    .launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.navigate("http://localhost:" + port + "/");

            String chip = ".run-chip[data-run-id='" + RUN_ID + "']";
            page.waitForSelector(chip);
            assertThat(page.innerText(chip)).containsIgnoringCase("from file");

            page.evaluate("() => setLanguage('ru')");
            assertThat(page.innerText(chip)).containsIgnoringCase("из файла");

            browser.close();
        }
    }

    @Test
    void anUnknownVersionIsRejected() {
        ResponseEntity<JsonNode> response = postSession(
                SESSION_JSON.replace("\"version\": 1", "\"version\": 99"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message").asText()).contains("version");
        assertThat(rest.getForObject("/api/runs", JsonNode.class)).isEmpty();
    }
}
