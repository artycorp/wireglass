package com.wireglass.listview;

import static org.assertj.core.api.Assertions.assertThat;

import com.wireglass.listview.client.capture.TrafficCaptureClient;
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
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import us.abstracta.jmeter.javadsl.JmeterDsl;

/**
 * End-to-end tests: boots the full Spring Boot app (in-process, on a random port) and drives the
 * real browser UI with Playwright against a local echo HTTP server (no external network needed).
 *
 * Run with: {@code mvn verify}  (installs the chromium browser automatically, then runs the tests).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrafficInspectorE2EIT {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(20);

    @LocalServerPort
    private int appPort;

    private String originalUserHome;
    private Playwright playwright;
    private Browser browser;
    private HttpServer echoServer;
    private int echoPort;

    @BeforeAll
    void setUp(@TempDir Path isolatedHome) throws IOException {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", isolatedHome.toString());

        echoServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        echoServer.createContext("/", this::echo);
        echoServer.createContext("/html", this::echoHtml);
        echoServer.createContext("/big", this::echoBig);
        echoServer.start();
        echoPort = echoServer.getAddress().getPort();

        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true));
    }

    private void echo(HttpExchange exchange) throws IOException {
        byte[] requestBytes = exchange.getRequestBody().readAllBytes();
        String received = new String(requestBytes, StandardCharsets.UTF_8).replace("\\", "\\\\")
                .replace('"', '\'');
        String body = "{\"method\":\"" + exchange.getRequestMethod()
                + "\",\"receivedBody\":\"" + received + "\"}";
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    private void echoHtml(HttpExchange exchange) throws IOException {
        String body = "<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 3.2 Final//EN\">\n"
                + "<title>405 Method Not Allowed</title>\n"
                + "<h1>Method Not Allowed</h1>\n"
                + "<p>The method is not allowed for the requested URL.</p>\n";
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html");
        exchange.sendResponseHeaders(200, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    private void echoBig(HttpExchange exchange) throws IOException {
        StringBuilder sb = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 400; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":").append(i).append(",\"name\":\"item-").append(i).append("\"}");
        }
        sb.append("]}");
        byte[] out = sb.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    @AfterAll
    void tearDown() {
        try {
            if (browser != null) {
                browser.close();
            }
            if (playwright != null) {
                playwright.close();
            }
            if (echoServer != null) {
                echoServer.stop(0);
            }
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @BeforeEach
    void clearBackend() throws Exception {
        // isolate tests: each test starts with an empty packet repository on the backend
        HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create(appUrl("/api/packets")))
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
    }

    @Test
    void runProducesPacketsInList() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            startRun(page, "GET", "", null);

            int rows = waitForRowCount(page, 2);
            assertThat(rows).isGreaterThanOrEqualTo(2);

            // every captured packet row should report status 200
            String tableText = page.innerText("#packet-body");
            assertThat(tableText).contains("GET").contains("200");

            // method and status are color-coded via classes (GET -> m-get, 2xx -> s2)
            assertThat(page.querySelector("#packet-body tr.pkt .c-method.m-get")).isNotNull();
            assertThat(page.querySelector("#packet-body tr.pkt .c-status.s2")).isNotNull();

            // run lifecycle reaches FINISHED and the status line reflects it
            page.waitForFunction(
                    "() => /finished/.test(document.querySelector('#run-status').textContent)",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(TEST_TIMEOUT.toMillis()));
            assertThat(page.innerText("#run-status")).contains("finished");
        }
    }

    @Test
    void timestampCellShowsTheWholeTimeWithoutEllipsis() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            startRun(page, "GET", "", null);
            waitForRowCount(page, 2);

            assertThat(page.innerText("#packet-body tr.pkt .c-time"))
                    .matches("\\d{2}:\\d{2}:\\d{2}\\.\\d{3}");
            assertThat((Boolean) page.evaluate(
                    "() => { const c = document.querySelector('#packet-body tr.pkt .c-time');"
                            + " return c.scrollWidth <= c.clientWidth; }"))
                    .isTrue();
        }
    }

    @Test
    void packetsExposeRunIdInHistoryAndLiveStream() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            startRun(page, "GET", "", null);
            waitForRowCount(page, 1);

            String runId = (String) page.evaluate("() => state.packets[0] && state.packets[0].runId");
            assertThat(runId).isNotBlank();
        }
    }

    @Test
    void runListShowsMultipleRunsAndFiltersTableBySelection() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));

            startRun(page, "GET", "", null);
            // 1 thread x 2 iterations = 2 packets; wait for the whole run to land before
            // snapshotting firstCount. A bare >=1 wait races the second packet, and the
            // exact-equality filter assertion below would then never match on a slow runner.
            int firstCount = waitForRowCount(page, 2);
            page.waitForSelector("#run-list .run-chip",
                    new Page.WaitForSelectorOptions().setTimeout(TEST_TIMEOUT.toMillis()));

            String firstRunId = page.getAttribute("#run-list .run-chip", "data-run-id");
            assertThat(firstRunId).isNotBlank();
            assertThat(firstCount).isGreaterThan(0);

            startRun(page, "POST", "{\"n\":2}", "application/json");
            page.waitForFunction(
                    "() => document.querySelectorAll('#run-list .run-chip').length >= 2",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(TEST_TIMEOUT.toMillis()));
            assertThat(page.locator("#run-list .run-chip").count()).isGreaterThanOrEqualTo(2);

            page.click("#run-list .run-chip[data-run-id='" + firstRunId + "']");
            page.waitForFunction(
                    "(expected) => document.querySelectorAll('#packet-body tr.pkt').length === expected",
                    firstCount,
                    new Page.WaitForFunctionOptions().setTimeout(TEST_TIMEOUT.toMillis()));

            int filteredCount = rowCount(page);
            assertThat(filteredCount).isEqualTo(firstCount);
        }
    }

    @Test
    void selectingPacketShowsRequestAndResponseBodies() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            String sentBody = "{\"hello\":\"world\",\"n\":42}";
            startRun(page, "POST", sentBody, "application/json");
            waitForRowCount(page, 1);

            // each row renders an inline timing waterfall bar
            assertThat(page.querySelector("#packet-body tr.pkt .wf-bar")).isNotNull();

            // open the first packet's detail -> the drawer slides in
            page.click("#packet-body tr.pkt");
            page.waitForSelector(".detail h2",
                    new Page.WaitForSelectorOptions().setTimeout(TEST_TIMEOUT.toMillis()));
            assertThat(page.isVisible("#detail-pane.open")).isTrue();

            // JSON bodies are rendered in a (highlighted) CodeMirror viewer
            assertThat(page.querySelector("#detail-pane .CodeMirror")).isNotNull();

            String detail = page.innerText("#detail-pane");
            // section titles are uppercased by CSS; assert case-insensitively
            assertThat(detail).containsIgnoringCase("Response headers");
            assertThat(detail).containsIgnoringCase("Response body");
            assertThat(detail).containsIgnoringCase("Request body");
            // the echo server reflects the method and the request body back in the response body
            assertThat(detail).contains("POST");
            assertThat(detail).contains("world");
            // the sent request body must be shown in its own section (pretty-printed JSON)
            assertThat(detail).contains("hello").contains("42");

            // Escape closes the drawer
            page.keyboard().press("Escape");
            page.waitForFunction(
                    "() => !document.querySelector('#detail-pane').classList.contains('open')",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(TEST_TIMEOUT.toMillis()));
        }
    }

    @Test
    void longHeadersWrapAndDirectionsAreColorSeparated() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            page.evaluate("""
                    () => renderDetail({
                      id: 'headers-test',
                      url: 'https://api.example.test/private',
                      label: 'headers-test',
                      method: 'GET',
                      status: '200',
                      success: true,
                      type: 'HTTP',
                      threadName: 'Thread Group 1-1',
                      elapsedMs: 12,
                      connectMs: 1,
                      latencyMs: 8,
                      requestHeaders: {
                        Connection: 'keep-alive',
                        Authorization: 'Bearer eyJzdWIiOiJsb2FkdGVzdCIsImV4cCI6MTc4MjgwMzc4N30.70f9ef54e20bccc280dce01575e634a001cf1c3abd0cf257fc0fdd03c0352393'
                      },
                      responseHeaders: {
                        'Content-Type': 'application/json'
                      },
                      requestBody: '',
                      responseBody: '{"ok":true}'
                    })
                    """);

            assertThat(page.querySelector("#detail-headers .headers-card.outgoing")).isNotNull();
            assertThat(page.querySelector("#detail-headers .headers-card.incoming")).isNotNull();
            assertThat(page.innerText("#detail-headers")).containsIgnoringCase("outgoing")
                    .containsIgnoringCase("incoming");
            assertThat((Boolean) page.evaluate("""
                    () => {
                      const value = [...document.querySelectorAll('#detail-headers td.v')]
                        .find(td => td.textContent.startsWith('Bearer '));
                      return value && value.scrollWidth <= value.clientWidth + 1;
                    }
                    """)).isTrue();
        }
    }

    @Test
    void responseSchemaValidationReportsMissingAndWrongType() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            openSettingsTab(page, "schema");
            page.fill("#schema-pattern", "/");
            page.selectOption("#schema-target", "response");
            page.fill("#schema-json", """
                    {
                      "type": "object",
                      "required": ["id", "method"],
                      "properties": {
                        "id": { "type": "number" },
                        "method": { "type": "number" }
                      }
                    }
                    """);
            page.click("#schema-save");
            assertThat(page.innerText("#schema-list")).containsIgnoringCase("response").contains("/");
            page.click("#settings-back");

            startRun(page, "GET", "", null);
            waitForRowCount(page, 1);

            page.click("#packet-body tr.pkt");
            page.waitForSelector("#detail-validation .validation-rule.invalid",
                    new Page.WaitForSelectorOptions().setTimeout(TEST_TIMEOUT.toMillis()));

            String validation = page.innerText("#detail-validation");
            assertThat(validation).containsIgnoringCase("Validation");
            assertThat(validation).containsIgnoringCase("response").contains("/");
            assertThat(validation).contains("$.id").contains("required");
            assertThat(validation).contains("$.method").contains("expected number");
        }
    }

    @Test
    void settingsPanelUsesTabsForSchemasAndDashboards() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));

            assertThat(page.querySelector("#schema-toggle")).isNull();
            assertThat(page.querySelector("#dashboard-toggle")).isNull();
            assertThat((Boolean) page.evaluate(
                    "() => document.querySelector('#settings-toggle').parentElement.classList.contains('topbar')"))
                    .isTrue();
            assertThat((Boolean) page.evaluate(
                    "() => document.querySelector('#run-toggle').nextElementSibling.id === 'settings-toggle'"))
                    .isTrue();
            assertThat((Boolean) page.evaluate(
                    "() => document.querySelector('#settings-toggle').classList.contains('run-toggle')"))
                    .isTrue();
            assertThat((Boolean) page.evaluate("""
                    () => {
                      const run = document.querySelector('#run-toggle').getBoundingClientRect();
                      const settings = document.querySelector('#settings-toggle').getBoundingClientRect();
                      return Math.abs(run.height - settings.height) <= 1;
                    }
                    """)).isTrue();
            assertThat((Boolean) page.evaluate("""
                    () => {
                      const run = document.querySelector('#run-toggle').getBoundingClientRect();
                      const settings = document.querySelector('#settings-toggle').getBoundingClientRect();
                      return Math.abs(run.width - settings.width) <= 1;
                    }
                    """)).isTrue();

            page.click("#settings-toggle");
            page.waitForSelector("#settings-view:not([hidden])",
                    new Page.WaitForSelectorOptions().setTimeout(TEST_TIMEOUT.toMillis()));
            assertThat(page.isVisible("#traffic-view")).isFalse();
            assertThat(page.isVisible("#settings-panel")).isTrue();
            assertThat(page.isVisible("#schema-panel")).isTrue();
            assertThat(page.isVisible("#dashboard-panel")).isFalse();

            page.click(".settings-tab[data-settings-tab='dashboards']");
            assertThat(page.isVisible("#dashboard-panel")).isTrue();
            assertThat(page.isVisible("#schema-panel")).isFalse();
            assertThat(page.getAttribute(".settings-tab[data-settings-tab='dashboards']", "aria-selected"))
                    .isEqualTo("true");

            page.click("#settings-back");
            page.waitForFunction("() => document.querySelector('#settings-view').hidden",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(TEST_TIMEOUT.toMillis()));
            assertThat(page.isVisible("#traffic-view")).isTrue();

            page.click("#settings-toggle");
            page.waitForSelector("#settings-view:not([hidden])",
                    new Page.WaitForSelectorOptions().setTimeout(TEST_TIMEOUT.toMillis()));
            assertThat(page.isVisible("#dashboard-panel")).isTrue();
        }
    }

    @Test
    void topbarControlsShareOneHeightAndCarryTheLanguageSwitch() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));

            assertThat((Boolean) page.evaluate(
                    "() => document.querySelector('.language-options').parentElement.classList.contains('topbar')"))
                    .isTrue();
            assertThat(page.querySelector("#language-panel")).isNull();
            assertThat(page.querySelector("#settings-tab-language")).isNull();
            assertThat(page.innerText(".topbar .language-options")).contains("English").contains("Русский");

            assertThat((Boolean) page.evaluate("""
                    () => {
                      const ids = ['#run-toggle', '#settings-toggle', '#clear-btn',
                                   ".language-option[data-language='en']",
                                   ".language-option[data-language='ru']"];
                      const heights = ids.map(s => document.querySelector(s).getBoundingClientRect().height);
                      return Math.max(...heights) - Math.min(...heights) <= 1;
                    }
                    """)).isTrue();

            page.click(".language-option[data-language='ru']");
            assertThat(page.innerText("#clear-btn")).isEqualTo("Очистить");
        }
    }

    @Test
    void clearButtonIsOnMainToolbarAndRequiresConfirmation() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            startRun(page, "GET", "", null);
            waitForRowCount(page, 1);

            assertThat(page.isVisible("#clear-btn")).isTrue();

            page.onceDialog(dialog -> dialog.dismiss());
            page.click("#clear-btn");
            assertThat(rowCount(page)).isGreaterThan(0);

            page.onceDialog(dialog -> dialog.accept());
            page.click("#clear-btn");
            page.waitForFunction(
                    "() => document.querySelectorAll('#packet-body tr.pkt').length === 0",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(TEST_TIMEOUT.toMillis()));
            assertThat(page.innerText("#packet-count")).contains("0 packets");
        }
    }

    @Test
    void filterHidesNonMatchingPackets() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            startRun(page, "GET", "", null);
            int total = waitForRowCount(page, 2);
            assertThat(total).isGreaterThanOrEqualTo(2);

            // type a string that matches no captured url/label -> list empties
            page.fill("#filter-text", "zzz-no-such-url");
            page.waitForFunction(
                    "() => document.querySelectorAll('#packet-body tr.pkt').length === 0",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(TEST_TIMEOUT.toMillis()));

            // clear the filter -> rows come back
            page.fill("#filter-text", "");
            page.waitForFunction(
                    "() => document.querySelectorAll('#packet-body tr.pkt').length >= " + total,
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(TEST_TIMEOUT.toMillis()));
        }
    }

    @Test
    void statusChipFiltersByClassAndResetRestores() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            startRun(page, "GET", "", null);  // echo server always answers 200 (a 2xx)
            int total = waitForRowCount(page, 2);
            assertThat(total).isGreaterThanOrEqualTo(2);

            // the active-filters summary is hidden until a filter is applied
            assertThat(page.isVisible("#active-filters")).isFalse();

            // filter to 5xx only -> every 2xx row is hidden and the empty-state appears
            page.click(".chip[data-status='5']");
            page.waitForFunction(
                    "() => document.querySelectorAll('#packet-body tr.pkt').length === 0",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(TEST_TIMEOUT.toMillis()));
            assertThat(page.innerText("#packet-body")).containsIgnoringCase("No packets match");
            assertThat(page.isVisible("#active-filters")).isTrue();

            // Reset clears the facet and the rows come back
            page.click("#reset-filters");
            page.waitForFunction(
                    "() => document.querySelectorAll('#packet-body tr.pkt').length >= " + total,
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(TEST_TIMEOUT.toMillis()));
        }
    }

    @Test
    void clickingLatencyHeaderSortsAndCyclesIndicator() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            startRun(page, "GET", "", null);
            waitForRowCount(page, 2);

            String header = "th[data-sort='latency']";

            // first click -> ascending; rendered latency values are non-decreasing
            page.click(header);
            assertThat(page.getAttribute(header, "aria-sort")).isEqualTo("ascending");
            assertThat(latenciesNonDecreasing(page)).isTrue();

            // second click -> descending
            page.click(header);
            assertThat(page.getAttribute(header, "aria-sort")).isEqualTo("descending");

            // third click -> off (back to insertion order)
            page.click(header);
            assertThat(page.getAttribute(header, "aria-sort")).isEqualTo("none");
        }
    }

    private static boolean latenciesNonDecreasing(Page page) {
        return (Boolean) page.evaluate(
                "() => { const v = [...document.querySelectorAll('#packet-body .wf-ms')]"
                + ".map(e => parseInt(e.textContent, 10));"
                + " for (let i = 1; i < v.length; i++) if (v[i] < v[i - 1]) return false; return true; }");
    }

    @Test
    void htmlResponseBodyIsHighlighted() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            page.click("#run-toggle");
            page.fill("#f-url", echoUrl("/html"));   // returns a text/html body
            page.selectOption("#f-method", "GET");
            page.fill("#f-threads", "1");
            page.fill("#f-iterations", "1");
            page.click("button.primary");
            waitForRowCount(page, 1);

            page.click("#packet-body tr.pkt");
            page.waitForSelector("#detail-pane .CodeMirror",
                    new Page.WaitForSelectorOptions().setTimeout(TEST_TIMEOUT.toMillis()));

            // HTML is highlighted (CodeMirror emits tag tokens), not dumped as plain text
            assertThat(page.querySelector("#detail-pane .cm-tag")).isNotNull();
            assertThat(page.innerText("#detail-pane")).containsIgnoringCase("Method Not Allowed");
        }
    }

    @Test
    void largeBodyViewerIsBoundedAndScrolls() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            page.click("#run-toggle");
            page.fill("#f-url", echoUrl("/big"));   // ~400-entry JSON, pretty-prints to many lines
            page.selectOption("#f-method", "GET");
            page.fill("#f-threads", "1");
            page.fill("#f-iterations", "1");
            page.click("button.primary");
            waitForRowCount(page, 1);

            page.click("#packet-body tr.pkt");
            page.waitForSelector("#detail-pane .cm-host .CodeMirror",
                    new Page.WaitForSelectorOptions().setTimeout(TEST_TIMEOUT.toMillis()));

            // the viewer is capped (does not grow to thousands of px) and scrolls internally
            double height = ((Number) page.evaluate(
                    "() => document.querySelector('#detail-pane .cm-host .CodeMirror')"
                    + ".getBoundingClientRect().height")).doubleValue();
            double viewport = ((Number) page.evaluate("() => window.innerHeight")).doubleValue();
            assertThat(height).isLessThanOrEqualTo(viewport * 0.6);
            // large bodies render with line numbers (virtualized scroll)
            assertThat(page.querySelector("#detail-pane .CodeMirror-linenumber")).isNotNull();
        }
    }

    @Test
    void expandButtonOpensFullScreenBodyViewer() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            page.click("#run-toggle");
            page.fill("#f-url", echoUrl("/big"));
            page.selectOption("#f-method", "GET");
            page.fill("#f-threads", "1");
            page.fill("#f-iterations", "1");
            page.click("button.primary");
            waitForRowCount(page, 1);

            page.click("#packet-body tr.pkt");
            page.waitForSelector("#detail-pane .body-expand",
                    new Page.WaitForSelectorOptions().setTimeout(TEST_TIMEOUT.toMillis()));

            // expand -> full-screen modal with a CodeMirror viewer
            page.click("#detail-pane .body-expand");
            page.waitForSelector("#body-modal .CodeMirror",
                    new Page.WaitForSelectorOptions().setTimeout(TEST_TIMEOUT.toMillis()));
            assertThat(page.isVisible("#body-modal")).isTrue();

            // Escape closes it
            page.keyboard().press("Escape");
            page.waitForFunction(
                    "() => document.querySelector('#body-modal').hidden",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(TEST_TIMEOUT.toMillis()));
        }
    }

    @Test
    void modalBodySearchFindsMatchesAndSupportsRegex() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            page.click("#run-toggle");
            page.fill("#f-url", echoUrl("/big"));   // many "item-N" entries
            page.selectOption("#f-method", "GET");
            page.fill("#f-threads", "1");
            page.fill("#f-iterations", "1");
            page.click("button.primary");
            waitForRowCount(page, 1);

            page.click("#packet-body tr.pkt");
            page.waitForSelector("#detail-pane .body-expand",
                    new Page.WaitForSelectorOptions().setTimeout(TEST_TIMEOUT.toMillis()));
            page.click("#detail-pane .body-expand");
            page.waitForSelector("#body-modal .CodeMirror",
                    new Page.WaitForSelectorOptions().setTimeout(TEST_TIMEOUT.toMillis()));

            // plain substring search highlights matches and reports a count
            page.fill("#bm-search", "item-5");
            page.waitForSelector("#body-modal .cm-search-hit",
                    new Page.WaitForSelectorOptions().setTimeout(TEST_TIMEOUT.toMillis()));
            assertThat(page.innerText("#bm-count")).contains("/");

            // regex search
            page.check("#bm-regex");
            page.fill("#bm-search", "item-\\d+");
            page.waitForFunction(
                    "() => document.querySelectorAll('#body-modal .cm-search-hit').length > 0",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(TEST_TIMEOUT.toMillis()));

            // an invalid regex is flagged, not thrown
            page.fill("#bm-search", "[unclosed");
            page.waitForFunction(
                    "() => document.querySelector('#bm-search').classList.contains('invalid')",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(TEST_TIMEOUT.toMillis()));
        }
    }

    @Test
    void rawFormattedToggleSwitchesBodyView() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            startRun(page, "GET", "", null);   // echo answers with JSON -> formatted by default
            waitForRowCount(page, 1);

            page.click("#packet-body tr.pkt");
            page.waitForSelector("#detail-pane .cm-host .CodeMirror",
                    new Page.WaitForSelectorOptions().setTimeout(TEST_TIMEOUT.toMillis()));

            // switch to Raw -> the highlighted editor is replaced by a plain <pre>
            page.click("#detail-pane .body-toggle .bt[data-raw='1']");
            page.waitForFunction(
                    "() => { const v = document.querySelector('#detail-pane .cm-host');"
                    + " return v && !v.querySelector('.CodeMirror') && !!v.querySelector('pre'); }",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(TEST_TIMEOUT.toMillis()));

            // switch back to Formatted -> CodeMirror returns
            page.click("#detail-pane .body-toggle .bt[data-raw='0']");
            page.waitForSelector("#detail-pane .cm-host .CodeMirror",
                    new Page.WaitForSelectorOptions().setTimeout(TEST_TIMEOUT.toMillis()));
        }
    }

    @Test
    void formatButtonPrettyPrintsRequestBody() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            page.click("#run-toggle");
            page.selectOption("#f-method", "POST");  // reveals the body editor
            page.evaluate("() => state.bodyEditor.setValue('{\"a\":1,\"b\":2}')");
            page.click("#f-body-format");

            String formatted = (String) page.evaluate("() => state.bodyEditor.getValue()");
            assertThat(formatted).contains("\n");        // pretty-printed across multiple lines
            assertThat(formatted).contains("\"a\": 1");   // 2-space indentation
        }
    }

    @Test
    void externalJmeterDslTestAppearsInForm() throws Exception {
        // Open the UI first so its SSE stream is live, then run a *standalone* jmeter-java-dsl
        // plan in a separate thread that streams its samples to the form via TrafficCaptureClient
        // (WebSocket -> /api/ingest). The packets must show up exactly like an in-form run.
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            page.waitForFunction(
                    "() => window.EventSource && true",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(TEST_TIMEOUT.toMillis()));

            Thread runner = new Thread(() -> {
                try {
                    JmeterDsl.testPlan(
                            JmeterDsl.threadGroup(1, 2, JmeterDsl.httpSampler(echoUrl("/"))),
                            new TrafficCaptureClient("http://localhost:" + appPort)
                    ).run();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "external-jmeter-dsl-run");
            runner.setDaemon(true);
            runner.start();

            int rows = waitForRowCount(page, 2);
            assertThat(rows).isGreaterThanOrEqualTo(2);
            assertThat(page.innerText("#packet-body")).contains("GET").contains("200");

            runner.join(TEST_TIMEOUT.toMillis());
        }
    }

    @Test
    void externalClientGeneratesRunIdWhenNotProvided() throws Exception {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            page.waitForFunction(
                    "() => window.EventSource && true",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(TEST_TIMEOUT.toMillis()));

            Thread runner = new Thread(() -> {
                try {
                    JmeterDsl.testPlan(
                            JmeterDsl.threadGroup(1, 1, JmeterDsl.httpSampler(echoUrl("/"))),
                            new TrafficCaptureClient("http://localhost:" + appPort)
                    ).run();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "external-jmeter-dsl-run-id");
            runner.setDaemon(true);
            runner.start();

            waitForRowCount(page, 1);
            page.waitForSelector("#run-list .run-chip",
                    new Page.WaitForSelectorOptions().setTimeout(TEST_TIMEOUT.toMillis()));
            assertThat((String) page.evaluate("() => state.packets[0] && state.packets[0].runId"))
                    .isNotBlank();

            runner.join(TEST_TIMEOUT.toMillis());
        }
    }

    private void startRun(Page page, String method, String body, String contentType) {
        page.click("#run-toggle");  // the load form lives in a collapsible panel
        page.fill("#f-url", echoUrl("/"));
        page.selectOption("#f-method", method);  // selecting POST/PUT/PATCH reveals the body field
        page.fill("#f-threads", "1");
        page.fill("#f-iterations", "2");
        page.fill("#f-contentType", contentType == null ? "" : contentType);
        if (body != null && !body.isEmpty()) {
            // the textarea is wrapped by CodeMirror, so set the value through its editor API
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

    private int waitForRowCount(Page page, int atLeast) {
        page.waitForFunction(
                "() => document.querySelectorAll('#packet-body tr.pkt').length >= " + atLeast,
                null,
                new Page.WaitForFunctionOptions().setTimeout(TEST_TIMEOUT.toMillis()));
        return rowCount(page);
    }

    private static int rowCount(Page page) {
        Object value = page.evaluate("document.querySelectorAll('#packet-body tr.pkt').length");
        return ((Number) value).intValue();
    }

    private String appUrl(String path) {
        return "http://localhost:" + appPort + path;
    }

    private String echoUrl(String path) {
        return "http://127.0.0.1:" + echoPort + path;
    }

    @Test
    void urlColumnGetsMostOfTheTableWidth() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            startRun(page, "GET", "", null);
            waitForRowCount(page, 1);
            // fixed layout: the url column must dominate; each fixed column stays narrow.
            Boolean ok = (Boolean) page.evaluate("""
                    () => {
                      const layout = getComputedStyle(document.querySelector('#packet-table')).tableLayout;
                      const url = document.querySelector('#packet-body tr.pkt .c-url').getBoundingClientRect().width;
                      const status = document.querySelector('#packet-body tr.pkt .c-status').getBoundingClientRect().width;
                      return layout === 'fixed' && url > status * 2;
                    }
                    """);
            assertThat(ok).isTrue();
        }
    }

    @Test
    void dashboardPanelAddsListsPersistsAndDeletesLinks() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            openSettingsTab(page, "dashboards");

            // choosing a preset fills the URL template and system
            page.selectOption("#dash-preset", "grafana");
            assertThat(page.inputValue("#dash-url")).contains("{fromMs}").contains("var-host={host}");

            page.fill("#dash-name", "Open in Grafana");
            page.selectOption("#dash-scope", "packet");
            page.click("#dash-save");
            assertThat(page.innerText("#dash-list")).contains("Open in Grafana").containsIgnoringCase("packet");

            // persists across reload (localStorage)
            page.reload();
            openSettingsTab(page, "dashboards");
            assertThat(page.innerText("#dash-list")).contains("Open in Grafana");

            // delete -> empty state
            page.click("#dash-list .dash-delete");
            page.waitForFunction(
                    "() => /No dashboard links/.test(document.querySelector('#dash-list').textContent)",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(TEST_TIMEOUT.toMillis()));
        }
    }

    @Test
    void dashboardPanelEditsAnExistingLinkInPlace() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            openSettingsTab(page, "dashboards");

            page.fill("#dash-name", "Open in Grafana");
            page.selectOption("#dash-scope", "packet");
            page.fill("#dash-url", "https://grafana.example/d/UID?host={host}");
            page.fill("#dash-match", "old-host");
            page.click("#dash-save");
            assertThat(page.innerText("#dash-list")).contains("Open in Grafana");

            // editing pre-fills the form and switches Save -> Update
            page.click("#dash-list .dash-edit");
            assertThat(page.inputValue("#dash-name")).isEqualTo("Open in Grafana");
            assertThat(page.inputValue("#dash-url")).isEqualTo("https://grafana.example/d/UID?host={host}");
            assertThat(page.inputValue("#dash-match")).isEqualTo("old-host");
            assertThat(page.innerText("#dash-save")).isEqualTo("Update");
            assertThat(page.isVisible("#dash-cancel-edit")).isTrue();
            assertThat((Boolean) page.evaluate(
                    "() => document.querySelector('#dash-list .schema-rule').classList.contains('editing')")).isTrue();

            page.fill("#dash-name", "Open in Grafana (renamed)");
            page.fill("#dash-match", "new-host");
            page.click("#dash-save");

            // updated in place: still exactly one row, new name, edit mode closed
            assertThat((Integer) page.evaluate(
                    "() => document.querySelectorAll('#dash-list .schema-rule').length")).isEqualTo(1);
            assertThat(page.innerText("#dash-list strong")).isEqualTo("Open in Grafana (renamed)");
            assertThat(page.innerText("#dash-save")).isEqualTo("Save");
            assertThat(page.isVisible("#dash-cancel-edit")).isFalse();

            // persists across reload, including the field that isn't rendered in the list (match)
            page.reload();
            openSettingsTab(page, "dashboards");
            assertThat(page.innerText("#dash-list strong")).isEqualTo("Open in Grafana (renamed)");
            page.click("#dash-list .dash-edit");
            assertThat(page.inputValue("#dash-match")).isEqualTo("new-host");

            // cancel restores the idle Save state without touching the link
            page.fill("#dash-name", "should not be saved");
            page.click("#dash-cancel-edit");
            assertThat(page.innerText("#dash-save")).isEqualTo("Save");
            assertThat(page.isVisible("#dash-cancel-edit")).isFalse();
            assertThat(page.inputValue("#dash-name")).isEqualTo("");
            assertThat(page.innerText("#dash-list strong")).isEqualTo("Open in Grafana (renamed)");
        }
    }

    @Test
    void dashboardUrlTemplateHighlightsVariablesInPreviewAndList() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            openSettingsTab(page, "dashboards");

            String template = "https://GRAFANA/d/UID?var-host={host}&from={fromMs}&to={toMs}";
            page.fill("#dash-name", "Grafana vars");
            page.fill("#dash-url", template);

            assertThat(page.innerText("#dash-url-preview")).contains("https://GRAFANA").contains("{host}");
            assertThat((Integer) page.evaluate(
                    "() => document.querySelectorAll('#dash-url-preview .template-var').length")).isEqualTo(3);

            page.click("#dash-save");
            assertThat(page.innerText("#dash-list")).contains("Grafana vars");
            assertThat((Integer) page.evaluate(
                    "() => document.querySelectorAll('#dash-list .template-var').length")).isEqualTo(3);
            assertThat(page.getAttribute("#dash-list .template-var[data-var='host']", "title"))
                    .contains("packet host");
        }
    }

    @Test
    void dashboardLinksShowSystemIconsInFormListAndDetail() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            openSettingsTab(page, "dashboards");

            page.selectOption("#dash-system", "splunk");
            assertThat(page.getAttribute(".system-select .system-icon", "alt")).isEqualTo("Splunk");

            page.fill("#dash-name", "Splunk host");
            page.selectOption("#dash-scope", "packet");
            page.fill("#dash-url", "https://splunk.example/search?host={host}");
            page.fill("#dash-match", "127.0.0.1");
            page.click("#dash-save");
            assertThat(page.getAttribute("#dash-list .schema-rule .system-icon", "alt")).isEqualTo("Splunk");

            page.click("#settings-toggle");
            startRun(page, "GET", "", null);
            waitForRowCount(page, 1);
            page.click("#packet-body tr.pkt");
            page.waitForSelector("#detail-dashboards-list .dash-link .system-icon",
                    new Page.WaitForSelectorOptions().setTimeout(TEST_TIMEOUT.toMillis()));
            assertThat(page.getAttribute("#detail-dashboards-list .dash-link .system-icon", "alt"))
                    .isEqualTo("Splunk");
        }
    }

    @Test
    void dashboardUrlTemplateSubstitutesEncodesAndValidatesScheme() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));

            String href = (String) page.evaluate(
                "() => buildDashboardUrl("
                + "'https://g/d/UID?h={host}&p={path}&from={fromMs}&to={toMs}&q={label}', "
                + "{ url:'https://api.example.com:8443/orders/42?x=1', label:'a\"b', "
                + "timestamp:'2026-06-30T10:00:00.000Z' })");
            assertThat(href).contains("h=api.example.com")
                    .contains("p=%2Forders%2F42")
                    .contains("q=a%22b");
            assertThat(href).matches(".*from=\\d+&to=\\d+.*");

            assertThat(page.evaluate("() => safeDashboardHref('javascript:alert(1)')")).isNull();
            assertThat(page.evaluate("() => safeDashboardHref('https://x/y')")).isEqualTo("https://x/y");

            assertThat((Boolean) page.evaluate(
                    "() => matchesLinkUrl('https://h/orders/42','orders')")).isTrue();
            assertThat((Boolean) page.evaluate(
                    "() => matchesLinkUrl('https://h/orders/42','users')")).isFalse();
            assertThat((Boolean) page.evaluate(
                    "() => matchesLinkUrl('https://h/orders/42','(')")).isFalse();

            // global (no packet): packet placeholders resolve to empty, not left literal
            String globalHref = (String) page.evaluate(
                    "() => buildDashboardUrl('https://x/{host}?from={fromMs}', null)");
            assertThat(globalHref).doesNotContain("%7B").startsWith("https://x/?from=");

            // invalid regex falls back to substring (positive branch)
            assertThat((Boolean) page.evaluate(
                    "() => matchesLinkUrl('https://h/a(b','a(')")).isTrue();
        }
    }

    @Test
    void detailPaneShowsMatchingDashboardLinksWithSafeHref() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            openSettingsTab(page, "dashboards");

            // matching packet link (echo host is 127.0.0.1); name carries a quote to prove escaping
            page.fill("#dash-name", "Grafana \"prod\"");
            page.selectOption("#dash-scope", "packet");
            page.fill("#dash-url", "https://grafana.example/d/UID?host={host}&from={fromMs}");
            page.fill("#dash-match", "127.0.0.1");
            page.click("#dash-save");

            // non-matching packet link
            page.fill("#dash-name", "Other");
            page.fill("#dash-url", "https://other/{host}");
            page.fill("#dash-match", "no-such-host");
            page.click("#dash-save");
            page.click("#settings-toggle");  // close panel

            startRun(page, "GET", "", null);
            waitForRowCount(page, 1);
            page.click("#packet-body tr.pkt");
            page.waitForSelector("#detail-dashboards .dash-link",
                    new Page.WaitForSelectorOptions().setTimeout(TEST_TIMEOUT.toMillis()));

            // only the matching link is shown, with a safe encoded href and safe rel/target
            int count = ((Number) page.evaluate(
                    "() => document.querySelectorAll('#detail-dashboards .dash-link').length")).intValue();
            assertThat(count).isEqualTo(1);
            com.microsoft.playwright.ElementHandle a = page.querySelector("#detail-dashboards .dash-link");
            assertThat(a.innerText()).contains("Grafana");
            assertThat(a.getAttribute("href")).startsWith("https://grafana.example/").contains("host=127.0.0.1");
            assertThat(a.getAttribute("rel")).contains("noopener");
            assertThat(a.getAttribute("target")).isEqualTo("_blank");
        }
    }

    @Test
    void navTabHighlightFollowsScroll() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            startRun(page, "GET", "", null);
            waitForRowCount(page, 1);
            page.click("#packet-body tr.pkt");
            page.waitForSelector(".detail h2",
                    new Page.WaitForSelectorOptions().setTimeout(TEST_TIMEOUT.toMillis()));
            // scrolling the raw section into view should activate the Raw tab without a click.
            page.evaluate("() => document.getElementById('detail-raw').scrollIntoView({block:'start'})");
            page.waitForFunction(
                    "() => document.querySelector('.detail-tab[data-jump=raw]').classList.contains('active')",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(TEST_TIMEOUT.toMillis()));
            assertThat(page.querySelector(".detail-tab.active").getAttribute("data-jump")).isEqualTo("raw");
        }
    }

    @Test
    void globalDashboardLinkAppearsInTopBar() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            openSettingsTab(page, "dashboards");
            page.fill("#dash-name", "Grafana home");
            page.selectOption("#dash-scope", "global");
            page.fill("#dash-url", "https://grafana.example/home");
            page.click("#dash-save");

            page.waitForSelector("#global-links a",
                    new Page.WaitForSelectorOptions().setTimeout(TEST_TIMEOUT.toMillis()));
            com.microsoft.playwright.ElementHandle a = page.querySelector("#global-links a");
            assertThat(a.innerText()).contains("Grafana home");
            assertThat(a.getAttribute("href")).isEqualTo("https://grafana.example/home");
            assertThat(a.getAttribute("rel")).contains("noopener");
            assertThat(a.getAttribute("target")).isEqualTo("_blank");
        }
    }

    @Test
    void xmlBodiesArePrettyPrinted() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            String pretty = (String) page.evaluate(
                    "() => prettyXml('<a><b>1</b><c>2</c></a>')");
            assertThat(pretty).contains("\n");
            assertThat(pretty).contains("  <b>1</b>");
        }
    }

    @Test
    void traceHeaderRendersConfiguredLink() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            // buildTraceUrl substitutes {value} and rejects non-http(s) templates.
            assertThat((String) page.evaluate(
                    "() => buildTraceUrl('https://sfx.test/trace/{value}', 'abc/def')"))
                    .isEqualTo("https://sfx.test/trace/abc%2Fdef");
            assertThat(page.evaluate(
                    "() => buildTraceUrl('javascript:alert(1)', 'x')")).isNull();

            // Use a header distinct from the seeded default (x-b3-traceid) so this asserts the
            // user-configured template, not the seed (traceLinkFor returns the first match).
            openSettingsTab(page, "trace");
            page.fill("#trace-header", "x-request-id");
            page.fill("#trace-url", "https://sfx.test/trace/{value}");
            page.click("#trace-save");
            assertThat(page.innerText("#trace-list")).containsIgnoringCase("x-request-id");
            page.click("#settings-back");

            page.evaluate("""
                    () => renderDetail({
                      id: 'trace-test', url: 'https://x.test/', label: 't', method: 'GET',
                      status: '200', success: true, type: 'HTTP', threadName: 'th',
                      elapsedMs: 1, connectMs: 0, latencyMs: 1,
                      requestHeaders: { 'X-Request-Id': 'deadbeef' },
                      responseHeaders: {}, requestBody: '', responseBody: '{}'
                    })
                    """);
            var link = page.querySelector("#detail-headers a.trace-link");
            assertThat(link).isNotNull();
            assertThat(link.getAttribute("href")).isEqualTo("https://sfx.test/trace/deadbeef");
            assertThat(link.getAttribute("target")).isEqualTo("_blank");
            assertThat(link.getAttribute("rel")).contains("noopener");
        }
    }

    @Test
    void tracePanelEditsAnExistingLinkInPlace() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            openSettingsTab(page, "trace");

            // the panel is seeded with one default link (x-b3-traceid); this adds a second
            page.fill("#trace-header", "x-request-id");
            page.fill("#trace-url", "https://sfx.test/trace/{value}");
            page.click("#trace-save");
            assertThat((Integer) page.evaluate(
                    "() => document.querySelectorAll('#trace-list .schema-rule').length")).isEqualTo(2);

            // editing pre-fills the form and switches Save -> Update
            page.click("#trace-list .schema-rule:nth-child(2) .trace-edit");
            assertThat(page.inputValue("#trace-header")).isEqualTo("x-request-id");
            assertThat(page.inputValue("#trace-url")).isEqualTo("https://sfx.test/trace/{value}");
            assertThat(page.innerText("#trace-save")).isEqualTo("Update");
            assertThat(page.isVisible("#trace-cancel-edit")).isTrue();
            assertThat((Boolean) page.evaluate(
                    "() => document.querySelector('#trace-list .schema-rule:nth-child(2)')"
                            + ".classList.contains('editing')")).isTrue();

            page.fill("#trace-header", "x-correlation-id");
            page.fill("#trace-url", "https://sfx.test/v2/{value}");
            page.click("#trace-save");

            // updated in place: still two rows, edit mode closed
            assertThat((Integer) page.evaluate(
                    "() => document.querySelectorAll('#trace-list .schema-rule').length")).isEqualTo(2);
            // header cells are uppercased by CSS, so compare case-insensitively
            assertThat(page.innerText("#trace-list"))
                    .containsIgnoringCase("x-correlation-id").doesNotContainIgnoringCase("x-request-id");
            assertThat(page.innerText("#trace-save")).isEqualTo("Save");
            assertThat(page.isVisible("#trace-cancel-edit")).isFalse();

            // persists across reload
            page.reload();
            openSettingsTab(page, "trace");
            assertThat(page.innerText("#trace-list")).containsIgnoringCase("x-correlation-id");

            // cancel restores the idle Save state without touching the link
            page.click("#trace-list .schema-rule:nth-child(2) .trace-edit");
            page.fill("#trace-header", "should-not-be-saved");
            page.click("#trace-cancel-edit");
            assertThat(page.innerText("#trace-save")).isEqualTo("Save");
            assertThat(page.isVisible("#trace-cancel-edit")).isFalse();
            assertThat(page.inputValue("#trace-header")).isEqualTo("");
            assertThat(page.innerText("#trace-list"))
                    .containsIgnoringCase("x-correlation-id").doesNotContainIgnoringCase("should-not-be-saved");
        }
    }

    @Test
    void setCookieHeaderIsSplitIntoNameValueTable() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            String parsed = (String) page.evaluate(
                    "() => JSON.stringify(parseCookie('Set-Cookie', 'sid=abc123; Path=/; HttpOnly'))");
            assertThat(parsed).contains("sid").contains("abc123").contains("Path").contains("HttpOnly");

            page.evaluate("""
                    () => renderDetail({
                      id: 'cookie-test', url: 'https://x.test/', label: 'c', method: 'GET',
                      status: '200', success: true, type: 'HTTP', threadName: 't',
                      elapsedMs: 1, connectMs: 0, latencyMs: 1,
                      requestHeaders: { Cookie: 'a=1; b=2' },
                      responseHeaders: { 'Set-Cookie': 'sid=abc123; Path=/; HttpOnly' },
                      requestBody: '', responseBody: '{}'
                    })
                    """);
            assertThat(page.querySelector("#detail-headers .cookie-table")).isNotNull();
            String headers = page.innerText("#detail-headers");
            assertThat(headers).contains("sid").contains("abc123").contains("HttpOnly");
        }
    }

    @Test
    void headerNamesAreVisibleNextToTheirValues() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            page.evaluate("""
                    () => renderDetail({
                      id: 'hdr-test', url: 'https://x.test/', label: 'h', method: 'GET',
                      status: '200', success: true, type: 'HTTP', threadName: 'th',
                      elapsedMs: 1, connectMs: 0, latencyMs: 1,
                      requestHeaders: { 'Content-Type': 'application/json' },
                      responseHeaders: { 'Access-Control-Allow-Credentials': 'true' },
                      requestBody: '', responseBody: '{}'
                    })
                    """);
            String headers = page.innerText("#detail-headers");
            assertThat(headers).contains("Content-Type").contains("Access-Control-Allow-Credentials");

            // innerText reports clipped text too, so assert the name column is actually laid out.
            var clipped = (Boolean) page.evaluate(
                    "() => [...document.querySelectorAll('#detail-headers td.k')]"
                            + ".some(td => td.scrollWidth > td.clientWidth + 1 || td.clientWidth < 60)");
            assertThat(clipped).isFalse();
        }
    }

    @Test
    void listRowShowsSchemaValidationIndicator() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            openSettingsTab(page, "schema");
            page.fill("#schema-pattern", "/");
            page.selectOption("#schema-target", "response");
            page.fill("#schema-json", "{\"type\":\"object\",\"required\":[\"missing\"]}");
            page.click("#schema-save");
            page.click("#settings-back");

            startRun(page, "GET", "", null);
            waitForRowCount(page, 1);
            assertThat(page.querySelector("#packet-body tr.pkt.pkt-invalid")).isNotNull();
            assertThat(page.querySelector("#packet-body tr.pkt .c-valid .valid-shield")).isNotNull();
        }
    }

    // Both body sections are always present, so the pane never looks like a packet "has no
    // response" when it merely has an empty one. An omitted section is indistinguishable from
    // a missing capture, which is what made this read as "the response body disappeared".
    @Test
    void bothBodySectionsAreAlwaysRendered() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));

            // both bodies present -> two viewers with their own content
            renderPacketWithBodies(page, "both", "{\"req\":1}", "{\"resp\":2}");
            assertThat(bodyBlockCount(page)).isEqualTo(2);
            String both = page.innerText("#detail-bodies");
            assertThat(both).containsIgnoringCase("Request body").containsIgnoringCase("Response body");
            assertThat(both).contains("req").contains("resp");

            // empty request body (a plain GET) still gets its own labelled, empty section
            renderPacketWithBodies(page, "get", "", "{\"resp\":2}");
            assertThat(bodyBlockCount(page)).isEqualTo(2);
            assertThat(page.innerText("#detail-bodies"))
                    .containsIgnoringCase("Request body").containsIgnoringCase("Response body");
            assertThat(page.querySelector("#detail-bodies .body-empty")).isNotNull();

            // empty response body is shown the same way
            renderPacketWithBodies(page, "empty-resp", "{\"req\":1}", "");
            assertThat(bodyBlockCount(page)).isEqualTo(2);
            assertThat(page.innerText("#detail-bodies"))
                    .containsIgnoringCase("Request body").containsIgnoringCase("Response body");

            // both empty -> still two sections, both marked empty
            renderPacketWithBodies(page, "neither", "", "");
            assertThat(bodyBlockCount(page)).isEqualTo(2);
            assertThat((Integer) page.evaluate(
                    "() => document.querySelectorAll('#detail-bodies .body-empty').length")).isEqualTo(2);
        }
    }

    private void renderPacketWithBodies(Page page, String id, String requestBody, String responseBody) {
        page.evaluate("""
                ([id, req, resp]) => renderDetail({
                  id: id, url: '/', label: 't', method: 'POST',
                  status: '200', success: true, type: 'HTTP', threadName: 'th',
                  elapsedMs: 1, connectMs: 0, latencyMs: 1,
                  requestHeaders: {}, responseHeaders: {},
                  requestBody: req, responseBody: resp
                })
                """, java.util.List.of(id, requestBody, responseBody));
    }

    private int bodyBlockCount(Page page) {
        return (Integer) page.evaluate(
                "() => document.querySelectorAll('#detail-bodies .body-block').length");
    }

    @Test
    void invalidResponseBodyIsTinted() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));
            openSettingsTab(page, "schema");
            page.fill("#schema-pattern", "/");
            page.selectOption("#schema-target", "response");
            page.fill("#schema-json", "{\"type\":\"object\",\"required\":[\"missing\"]}");
            page.click("#schema-save");
            page.click("#settings-back");

            page.evaluate("""
                    () => renderDetail({
                      id: 'tint-test', url: '/', label: 't', method: 'GET',
                      status: '200', success: true, type: 'HTTP', threadName: 'th',
                      elapsedMs: 1, connectMs: 0, latencyMs: 1,
                      requestHeaders: {}, responseHeaders: {},
                      requestBody: '', responseBody: '{"present":1}'
                    })
                    """);
            page.waitForSelector("#detail-bodies .cm-body-invalid",
                    new Page.WaitForSelectorOptions().setTimeout(TEST_TIMEOUT.toMillis()));
            assertThat(page.querySelector("#detail-bodies .cm-body-invalid")).isNotNull();
        }
    }
}
