package com.artembelikov.listview;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Verifies the zero-config local file source ({@code ~/.wireglass/dashboards.json}), isolated
 * from any real home directory via a per-class temp dir. No {@code remote-config-url} is set, so
 * only the local file contributes to {@code /api/config/rules}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalOnlyDashboardConfigE2EIT {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(20);

    @LocalServerPort
    private int appPort;

    private String originalUserHome;
    private Path home;
    private Playwright playwright;
    private Browser browser;

    @BeforeAll
    void setUp(@TempDir Path tempHome) throws IOException {
        home = tempHome;
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Files.createDirectories(home.resolve(".wireglass"));
        Files.writeString(home.resolve(".wireglass").resolve("dashboards.json"), """
                {
                  "version": 1,
                  "schemas": [
                    {"id":"local-schema","name":"Local schema","pattern":"/","target":"response","schema":{"type":"object"}}
                  ],
                  "dashboards": [
                    {"id":"local-dash","name":"Local Grafana","system":"grafana","scope":"global","urlTemplate":"http://local.example/d"}
                  ]
                }
                """);

        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    void tearDown() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
        System.setProperty("user.home", originalUserHome);
    }

    @Test
    void localFileDashboardAndSchemaAppearTaggedLocalFile() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));

            openSettingsTab(page, "schema");
            waitForListContains(page, "#schema-list", "Local schema");
            assertThat(page.innerText("#schema-list"))
                    .contains("Local schema").containsIgnoringCase("local file");

            page.click(".settings-tab[data-settings-tab='dashboards']");
            waitForListContains(page, "#dash-list", "Local Grafana");
            assertThat(page.innerText("#dash-list"))
                    .contains("Local Grafana").containsIgnoringCase("local file");
        }
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
                List.of(selector, text),
                new Page.WaitForFunctionOptions().setTimeout(TEST_TIMEOUT.toMillis()));
    }

    private String appUrl(String path) {
        return "http://localhost:" + appPort + path;
    }
}
