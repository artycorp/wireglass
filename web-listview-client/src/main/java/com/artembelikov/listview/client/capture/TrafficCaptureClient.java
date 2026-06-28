package com.artembelikov.listview.client.capture;

import java.util.List;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.visualizers.SimpleDataWriter;
import us.abstracta.jmeter.javadsl.core.listeners.BaseListener;
import us.abstracta.jmeter.javadsl.core.listeners.DslListener;

/**
 * A jmeter-java-dsl listener that streams every captured sample of a test plan to the
 * jmeter-web-listview web form over a WebSocket connection.
 *
 * <p>Usage in any standalone jmeter-java-dsl test plan:
 * <pre>{@code
 * import static com.artembelikov.listview.client.capture.TrafficCaptureClient.trafficCaptureClient;
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

    public TrafficCaptureClient(String serverUrl) {
        this(serverUrl, DEFAULT_MAX_BODY_BYTES);
    }

    public TrafficCaptureClient(String serverUrl, int maxBodyBytes) {
        super("Traffic Capture Client", SimpleDataWriter.class);
        this.serverUrl = serverUrl;
        this.maxBodyBytes = maxBodyBytes;
    }

    public static TrafficCaptureClient trafficCaptureClient(String serverUrl) {
        return new TrafficCaptureClient(serverUrl);
    }

    @Override
    public TestElement buildTestElement() {
        return new CapturingReporter(new WsSink(serverUrl), defaultExtractors(), maxBodyBytes);
    }

    private static List<com.artembelikov.listview.client.protocol.PacketExtractor> defaultExtractors() {
        return List.of(
                new com.artembelikov.listview.client.protocol.HttpPacketExtractor(),
                new com.artembelikov.listview.client.protocol.WebsocketPacketExtractor(),
                new com.artembelikov.listview.client.protocol.TcpPacketExtractor());
    }
}
