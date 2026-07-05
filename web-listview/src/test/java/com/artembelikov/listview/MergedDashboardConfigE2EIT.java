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
 * Verifies that a local {@code ~/.wireglass/dashboards.json} merges with a configured
 * {@code remote-config-url}, with the local file winning on id collisions, for both schemas and
 * dashboards. Uses the same {@code remote-config/merge-test-remote.json} fixture as
 * {@code RemoteConfigServiceTest} (Task 1).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.listview.remote-config-url=classpath:/remote-config/merge-test-remote.json"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MergedDashboardConfigE2EIT {

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
                    {"id":"shared-schema","name":"Local version of shared schema","pattern":"/","target":"response","schema":{"type":"object","required":["fromLocal"]}}
                  ],
                  "dashboards": [
                    {"id":"shared-dashboard","name":"Local version of shared dashboard","system":"grafana","scope":"global","urlTemplate":"http://local.example/dash"}
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
    void localVersionWinsOnIdCollisionAndRemoteOnlyItemsStillAppear() {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.navigate(appUrl("/"));

            openSettingsTab(page, "schema");
            waitForListContains(page, "#schema-list", "Local version of shared schema");
            assertThat(page.innerText("#schema-list"))
                    .contains("Local version of shared schema").containsIgnoringCase("local file")
                    .contains("Remote-only schema").containsIgnoringCase("server")
                    .doesNotContain("Remote version of shared schema");

            page.click(".settings-tab[data-settings-tab='dashboards']");
            waitForListContains(page, "#dash-list", "Local version of shared dashboard");
            assertThat(page.innerText("#dash-list"))
                    .contains("Local version of shared dashboard").containsIgnoringCase("local file")
                    .contains("Remote-only dashboard").containsIgnoringCase("server")
                    .doesNotContain("Remote version of shared dashboard");
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
