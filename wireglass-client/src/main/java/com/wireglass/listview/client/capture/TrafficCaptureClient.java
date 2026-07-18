package com.wireglass.listview.client.capture;

import java.util.List;
import java.util.UUID;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.visualizers.SimpleDataWriter;
import us.abstracta.jmeter.javadsl.core.listeners.BaseListener;
import us.abstracta.jmeter.javadsl.core.listeners.DslListener;

/**
 * A jmeter-java-dsl listener that streams every captured sample of a test plan to the
 * Wireglass web form over a WebSocket connection.
 *
 * <p>Usage in any standalone jmeter-java-dsl test plan:
 * <pre>{@code
 * import static com.wireglass.listview.client.capture.TrafficCaptureClient.trafficCaptureClient;
 *
 * testPlan(
 *     threadGroup(2, 3, httpSampler("https://example.com")),
 *     trafficCaptureClient("http://localhost:8080")
 * ).run();
 * }</pre>
 *
 * <p>Requires the web form to be running; the captured packets then appear live in its list view.
 */
public class TrafficCaptureClient extends BaseListener implements DslListener {

    public static final int DEFAULT_MAX_BODY_BYTES = 262144;

    private final String serverUrl;
    private final int maxBodyBytes;
    private final UUID runId;

    public TrafficCaptureClient(String serverUrl) {
        this(serverUrl, DEFAULT_MAX_BODY_BYTES, null);
    }

    public TrafficCaptureClient(String serverUrl, int maxBodyBytes) {
        this(serverUrl, maxBodyBytes, null);
    }

    public TrafficCaptureClient(String serverUrl, int maxBodyBytes, UUID runId) {
        super("Traffic Capture Client", SimpleDataWriter.class);
        this.serverUrl = serverUrl;
        this.maxBodyBytes = maxBodyBytes;
        this.runId = runId;
    }

    public static TrafficCaptureClient trafficCaptureClient(String serverUrl) {
        return new TrafficCaptureClient(serverUrl);
    }

    public TrafficCaptureClient withRunId(UUID runId) {
        return new TrafficCaptureClient(serverUrl, maxBodyBytes, runId);
    }

    @Override
    public TestElement buildTestElement() {
        return new CapturingReporter(new WsSink(serverUrl, runId), defaultExtractors(), maxBodyBytes);
    }

    private static List<com.wireglass.listview.client.protocol.PacketExtractor> defaultExtractors() {
        return List.of(
                new com.wireglass.listview.client.protocol.HttpPacketExtractor(),
                new com.wireglass.listview.client.protocol.WebsocketPacketExtractor(),
                new com.wireglass.listview.client.protocol.TcpPacketExtractor());
    }
}
