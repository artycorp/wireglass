package com.artembelikov.listview;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * End-to-end tests for server- and URL-provided schema/dashboard config. Kept in its own
 * {@code @SpringBootTest} context, separate from {@link TrafficInspectorE2EIT}, because setting
 * {@code app.listview.remote-config-url} merges the fixture's schema/dashboard rows into every
 * page load — sharing a context with tests that assert exact rule/link counts would make those
 * tests see extra rows they don't expect.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.listview.remote-config-url=classpath:/remote-config/demo-rules.json"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServerConfigRulesE2EIT {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(20);

    @LocalServerPort
    private int appPort;

    private Playwright playwright;
    private Browser browser;
    private HttpServer echoServer;
    private int echoPort;

    @BeforeAll
    void setUp() throws IOException {
        echoServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        echoServer.createContext("/", this::echo);
        echoServer.createContext("/repo-rules.json", this::repoRules);
        echoServer.start();
        echoPort = echoServer.getAddress().getPort();

        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true));
    }

    private void echo(HttpExchange exchange) throws IOException {
        String body = "{\"method\":\"" + exchange.getRequestMethod() + "\"}";
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    private void repoRules(HttpExchange exchange) throws IOException {
        String body = """
                {
                  "version": 1,
                  "schemas": [
                    {
                      "id": "repo-response-required-status",
                      "name": "Repo response requires status",
                      "pattern": "/",
                      "target": "response",
                      "schema": { "type": "object", "required": ["status"] }
                    }
                  ]
                }
                """;
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    @AfterAll
    void tearDown() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
        if (echoServer != null) {
            echoServer.stop(0);
        }
    }

    @BeforeEach
    void clearBackend() throws Exception {
        HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create(appUrl("/api/packets")))
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
    }

    @Test
    void loadsServerSchemasAndDashboards() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));

            openSettingsTab(page, "schema");
            waitForListContains(page, "#schema-list", "Server response requires id");
            assertThat(page.innerText("#schema-list")).contains("Server response requires id").containsIgnoringCase("server");

            page.click(".settings-tab[data-settings-tab='dashboards']");
            waitForListContains(page, "#dash-list", "Server Grafana");
            assertThat(page.innerText("#dash-list")).contains("Server Grafana").containsIgnoringCase("server");
        }
    }

    @Test
    void serverSchemaCanBeDisabledWithoutDeletingIt() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            openSettingsTab(page, "schema");
            waitForListContains(page, "#schema-list", "Server response requires id");

            page.click("#schema-list .schema-toggle-server");
            assertThat(page.innerText("#schema-list")).contains("Server response requires id").contains("Enable");

            page.click("#settings-back");
            startRun(page, "GET", "", null);
            waitForRowCount(page, 1);
            page.click("#packet-body tr.pkt");
            assertThat(page.innerText("#detail-validation")).contains("No matching schema rules");

            page.reload();
            openSettingsTab(page, "schema");
            waitForListContains(page, "#schema-list", "Server response requires id");
            assertThat(page.innerText("#schema-list")).contains("Server response requires id").contains("Enable");
        }
    }

    @Test
    void serverDashboardCanBeDisabledWithoutDeletingIt() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            openSettingsTab(page, "dashboards");
            waitForListContains(page, "#dash-list", "Server Grafana");

            page.click("#dash-list .dash-toggle-server");
            assertThat(page.innerText("#dash-list")).contains("Server Grafana").contains("Enable");

            page.click("#settings-back");
            startRun(page, "GET", "", null);
            waitForRowCount(page, 1);
            page.click("#packet-body tr.pkt");
            assertThat(page.innerText("#detail-dashboards-list")).doesNotContain("Server Grafana");
        }
    }

    @Test
    void editingServerSchemaRuleCreatesAHighlightedLocalOverride() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            openSettingsTab(page, "schema");
            waitForListContains(page, "#schema-list", "Server response requires id");

            page.click("#schema-list .schema-edit");
            assertThat(page.inputValue("#schema-name")).isEqualTo("Server response requires id");
            page.fill("#schema-json", "{\"type\":\"object\",\"required\":[\"id\",\"status\"]}");
            page.click("#schema-save");

            assertThat((Boolean) page.evaluate(
                    "() => document.querySelector('#schema-list .schema-rule').classList.contains('overridden')"))
                    .isTrue();
            assertThat(page.innerText("#schema-list")).containsIgnoringCase("edited");

            page.reload();
            openSettingsTab(page, "schema");
            waitForListContains(page, "#schema-list", "Server response requires id");
            assertThat((Boolean) page.evaluate(
                    "() => document.querySelector('#schema-list .schema-rule').classList.contains('overridden')"))
                    .isTrue();

            page.click("#settings-back");
            startRun(page, "GET", "", null);
            waitForRowCount(page, 1);
            page.click("#packet-body tr.pkt");
            page.waitForSelector("#detail-validation .validation-rule.invalid",
                    new Page.WaitForSelectorOptions().setTimeout(TEST_TIMEOUT.toMillis()));
            assertThat(page.innerText("#detail-validation")).contains("$.status").contains("required");

            openSettingsTab(page, "schema");
            page.click("#schema-list .schema-reset");
            assertThat((Boolean) page.evaluate(
                    "() => document.querySelector('#schema-list .schema-rule').classList.contains('overridden')"))
                    .isFalse();
        }
    }

    @Test
    void loadsAndRemovesSchemaRulesFromAUrlRepository() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            openSettingsTab(page, "schema");

            page.fill("#schema-remote-url", repoUrl("/repo-rules.json"));
            page.click("#schema-remote-load");
            waitForListContains(page, "#schema-list", "Repo response requires status");
            assertThat(page.innerText("#schema-list")).containsIgnoringCase("url");
            assertThat(page.innerText("#schema-remote-sources")).contains(repoUrl("/repo-rules.json"));

            page.click("#settings-back");
            startRun(page, "GET", "", null);
            waitForRowCount(page, 1);
            page.click("#packet-body tr.pkt");
            page.waitForSelector("#detail-validation .validation-rule.invalid",
                    new Page.WaitForSelectorOptions().setTimeout(TEST_TIMEOUT.toMillis()));
            assertThat(page.innerText("#detail-validation")).contains("$.status").contains("required");

            page.reload();
            openSettingsTab(page, "schema");
            waitForListContains(page, "#schema-list", "Repo response requires status");

            page.click("#schema-remote-sources .schema-source-remove");
            page.waitForFunction(
                    "() => !document.querySelector('#schema-list').textContent.includes('Repo response requires status')",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(TEST_TIMEOUT.toMillis()));
        }
    }

    private void startRun(Page page, String method, String body, String contentType) {
        page.click("#run-toggle");
        page.fill("#f-url", echoUrl("/"));
        page.selectOption("#f-method", method);
        page.fill("#f-threads", "1");
        page.fill("#f-iterations", "2");
        page.fill("#f-contentType", contentType == null ? "" : contentType);
        if (body != null && !body.isEmpty()) {
            page.evaluate("v => state.bodyEditor.setValue(v)", body);
        }
        page.click("button.primary");
    }

    private void openSettingsTab(Page page, String tab) {
        if (page.querySelector("#settings-view[hidden]") != null) {
            page.click("#settings-toggle");
        }
        page.click(".settings-tab[data-settings-tab='" + tab + "']");
    }

    private void waitForListContains(Page page, String selector, String text) {
        page.waitForFunction(
                "([sel, needle]) => document.querySelector(sel) && document.querySelector(sel).textContent.includes(needle)",
                java.util.List.of(selector, text),
                new Page.WaitForFunctionOptions().setTimeout(TEST_TIMEOUT.toMillis()));
    }

    private int waitForRowCount(Page page, int atLeast) {
        page.waitForFunction(
                "() => document.querySelectorAll('#packet-body tr.pkt').length >= " + atLeast,
                null,
                new Page.WaitForFunctionOptions().setTimeout(TEST_TIMEOUT.toMillis()));
        Object value = page.evaluate("document.querySelectorAll('#packet-body tr.pkt').length");
        return ((Number) value).intValue();
    }

    private String appUrl(String path) {
        return "http://localhost:" + appPort + path;
    }

    private String echoUrl(String path) {
        return "http://127.0.0.1:" + echoPort + path;
    }

    private String repoUrl(String path) {
        return "http://127.0.0.1:" + echoPort + path;
    }
}
