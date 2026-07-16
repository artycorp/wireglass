package com.artembelikov.listview;

import static org.assertj.core.api.Assertions.assertThat;

import com.artembelikov.listview.jmeter.WireglassBackendListener;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Verifies the stock-JMeter {@link WireglassBackendListener} without a real JMeter distribution:
 * it drives the plugin's {@code setupTest → handleSampleResults → teardownTest} lifecycle with a
 * synthetic {@link HTTPSampleResult} against the booted app, then asserts the packet arrived on the
 * same {@code /api/packets} history the browser reads — i.e. a {@code .jmx} run lands in Wireglass
 * exactly like a jmeter-dsl run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JmeterBackendListenerIT {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(15);

    @LocalServerPort
    private int appPort;

    private String originalUserHome;

    @BeforeEach
    void isolateHome(@TempDir Path isolatedHome) {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", isolatedHome.toString());
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", originalUserHome);
    }

    @Test
    void streamsStockJmeterSamplesToThePacketHistory() throws Exception {
        WireglassBackendListener listener = new WireglassBackendListener();
        BackendListenerContext context = new BackendListenerContext(Map.of(
                WireglassBackendListener.PARAM_SERVER_URL, "http://localhost:" + appPort,
                WireglassBackendListener.PARAM_MAX_BODY_BYTES, "262144",
                WireglassBackendListener.PARAM_RUN_ID, ""));

        listener.setupTest(context);
        try {
            listener.handleSampleResults(List.of(sample()), context);
        } finally {
            listener.teardownTest(context);
        }

        waitForPacketHistoryToContain("stock-jmeter-sample");
    }

    private static SampleResult sample() throws Exception {
        HTTPSampleResult result = new HTTPSampleResult();
        result.setSampleLabel("stock-jmeter-sample");
        result.setHTTPMethod("GET");
        result.setURL(new URL("http://echo.test/probe"));
        result.sampleStart();
        result.setContentType("application/json");
        result.setResponseData("{\"ok\":true}", "UTF-8");
        result.setResponseCode("200");
        result.setResponseMessage("OK");
        result.setSuccessful(true);
        result.sampleEnd();
        return result;
    }

    private void waitForPacketHistoryToContain(String needle) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + appPort + "/api/packets?limit=200"))
                .GET()
                .build();
        long deadline = System.nanoTime() + TEST_TIMEOUT.toNanos();
        String lastBody = "";
        while (System.nanoTime() < deadline) {
            lastBody = http.send(request, HttpResponse.BodyHandlers.ofString()).body();
            if (lastBody.contains(needle)) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(lastBody).as("packet history should contain the streamed sample").contains(needle);
    }
}
