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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

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

            // open the first packet's detail
            page.click("#packet-body tr.pkt");
            page.waitForSelector(".detail h2",
                    new Page.WaitForSelectorOptions().setTimeout(TEST_TIMEOUT.toMillis()));

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

    private void startRun(Page page, String method, String body, String contentType) {
        page.fill("#f-url", echoUrl("/"));
        page.selectOption("#f-method", method);
        page.fill("#f-threads", "1");
        page.fill("#f-iterations", "2");
        page.fill("#f-body", body);
        page.fill("#f-contentType", contentType == null ? "" : contentType);
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
