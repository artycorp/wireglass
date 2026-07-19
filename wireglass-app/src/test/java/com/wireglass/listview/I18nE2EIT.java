package com.wireglass.listview;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class I18nE2EIT {

    @LocalServerPort
    int port;

    private String originalUserHome;
    private Path tempHome;

    @BeforeEach
    void isolateHome() throws Exception {
        originalUserHome = System.getProperty("user.home");
        tempHome = Files.createTempDirectory("wireglass-i18n-home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterEach
    void restoreHome() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
    }

    private String baseUrl() {
        return "http://localhost:" + port + "/";
    }

    @Test
    void translationHelpersResolveKeysAndFallBack() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium()
                    .launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.navigate(baseUrl());

            Object english = page.evaluate("() => { setActiveLanguage('en'); return t('topbar.settings'); }");
            assertThat(english).isEqualTo("Settings");

            Object russian = page.evaluate("() => { setActiveLanguage('ru'); return t('topbar.settings'); }");
            assertThat(russian).isEqualTo("Настройки");

            Object missing = page.evaluate("() => t('no.such.key')");
            assertThat(missing).isEqualTo("no.such.key");

            Object interpolated = page.evaluate(
                    "() => { setActiveLanguage('en'); return t('count.packets', { n: 7 }); }");
            assertThat(interpolated).isEqualTo("7 packets");

            Object pluralFew = page.evaluate(
                    "() => { setActiveLanguage('ru'); return plural(3, ['пакет', 'пакета', 'пакетов']); }");
            assertThat(pluralFew).isEqualTo("пакета");

            Object pluralMany = page.evaluate(
                    "() => { setActiveLanguage('ru'); return plural(11, ['пакет', 'пакета', 'пакетов']); }");
            assertThat(pluralMany).isEqualTo("пакетов");

            Object pluralOne = page.evaluate(
                    "() => { setActiveLanguage('ru'); return plural(21, ['пакет', 'пакета', 'пакетов']); }");
            assertThat(pluralOne).isEqualTo("пакет");

            Object pluralEnglish = page.evaluate(
                    "() => { setActiveLanguage('en'); return plural(21, ['file', 'files', 'files']); }");
            assertThat(pluralEnglish).isEqualTo("files");

            browser.close();
        }
    }

    @Test
    void switchingLanguageTranslatesAnnotatedMarkupWithoutDestroyingChildren() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium()
                    .launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.navigate(baseUrl());

            assertThat(page.innerText("#settings-toggle")).contains("Settings");

            page.click("#settings-toggle");
            page.click("#settings-tab-language");
            page.click(".language-option[data-language='ru']");

            assertThat(page.innerText("#settings-toggle")).contains("Настройки");
            assertThat(page.querySelector("#settings-toggle .caret")).isNotNull();
            assertThat(page.getAttribute("html", "lang")).isEqualTo("ru");

            browser.close();
        }
    }

    @Test
    void runFormIsTranslated() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium()
                    .launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.navigate(baseUrl());

            page.click("#settings-toggle");
            page.click("#settings-tab-language");
            page.click(".language-option[data-language='ru']");
            page.click("#settings-back");
            page.click("#run-toggle");

            assertThat(page.innerText("#run-toggle")).contains("Новый прогон");
            assertThat(page.innerText("#run-form")).containsIgnoringCase("адрес")
                    .containsIgnoringCase("потоки").containsIgnoringCase("итерации");
            assertThat(page.innerText(".run-actions")).contains("Запустить").contains("Демо").contains("Стоп");
            assertThat(page.getAttribute("#f-body-format", "title")).isEqualTo("Форматировать JSON");
            assertThat(page.querySelector("#f-url")).isNotNull();
            assertThat(page.querySelector("#f-body-err")).isNotNull();

            browser.close();
        }
    }

    @Test
    void filterToolbarAndTableHeadersAreTranslated() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium()
                    .launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.navigate(baseUrl());

            page.click("#settings-toggle");
            page.click("#settings-tab-language");
            page.click(".language-option[data-language='ru']");
            page.click("#settings-back");

            assertThat(page.innerText("#run-all")).isEqualTo("Все прогоны");
            String headers = page.innerText("#packet-table thead");
            assertThat(headers).containsIgnoringCase("время").containsIgnoringCase("тип")
                    .containsIgnoringCase("метод").containsIgnoringCase("статус");

            String facets = page.innerText(".facets");
            assertThat(facets).contains("все").contains("любой");
            assertThat(facets).contains("HTTP").contains("WS").contains("TCP").contains("2xx");

            assertThat(page.getAttribute("#detail-restore", "title")).isEqualTo("Показать инспектор");

            browser.close();
        }
    }
}
