package com.artembelikov.listview;

import static org.assertj.core.api.Assertions.assertThat;

import com.artembelikov.listview.client.capture.TrafficCaptureClient;
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

    private Playwright playwright;
    private Browser browser;
    private HttpServer echoServer;
    private int echoPort;

    @BeforeAll
    void setUp() throws IOException {
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
}
