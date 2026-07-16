package com.artembelikov.listview.jmeter;

import com.artembelikov.listview.client.capture.SampleCapture;
import com.artembelikov.listview.client.capture.WsSink;
import com.artembelikov.listview.client.protocol.HttpPacketExtractor;
import com.artembelikov.listview.client.protocol.PacketExtractor;
import com.artembelikov.listview.client.protocol.TcpPacketExtractor;
import com.artembelikov.listview.client.protocol.WebsocketPacketExtractor;
import java.util.List;
import java.util.UUID;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.visualizers.backend.AbstractBackendListenerClient;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A stock-JMeter {@code BackendListenerClient} that streams every sample of a {@code .jmx} test plan
 * (GUI or {@code -n} CLI) to a running Wireglass app, exactly like the jmeter-dsl
 * {@code TrafficCaptureClient} does.
 *
 * <p>Packaged as a self-contained {@code lib/ext} jar (see the module's {@code maven-shade-plugin}
 * config) so JMeter can load it. Add a Backend Listener to the test plan and set its implementation
 * class to {@code com.artembelikov.listview.jmeter.WireglassBackendListener}; the {@code serverUrl}
 * argument points at the app.
 *
 * <p>Everything downstream of a {@link SampleResult} — the extractor chain, {@code CapturedPacket},
 * the JSON wire format, and the WebSocket transport ({@link WsSink} to {@code /api/ingest}) — is the
 * same code the jmeter-dsl client uses; this class is only a different front door onto it.
 */
public class WireglassBackendListener extends AbstractBackendListenerClient {

    private static final Logger LOG = LoggerFactory.getLogger(WireglassBackendListener.class);

    public static final String PARAM_SERVER_URL = "serverUrl";
    public static final String PARAM_MAX_BODY_BYTES = "maxBodyBytes";
    public static final String PARAM_RUN_ID = "runId";

    private static final String DEFAULT_SERVER_URL = "http://localhost:8080";
    private static final int DEFAULT_MAX_BODY_BYTES = 262144;

    private transient WsSink sink;
    private transient SampleCapture capture;

    @Override
    public Arguments getDefaultParameters() {
        Arguments args = new Arguments();
        args.addArgument(PARAM_SERVER_URL, DEFAULT_SERVER_URL);
        args.addArgument(PARAM_MAX_BODY_BYTES, String.valueOf(DEFAULT_MAX_BODY_BYTES));
        args.addArgument(PARAM_RUN_ID, "");
        return args;
    }

    @Override
    public void setupTest(BackendListenerContext context) throws Exception {
        String serverUrl = context.getParameter(PARAM_SERVER_URL, DEFAULT_SERVER_URL);
        int maxBodyBytes = context.getIntParameter(PARAM_MAX_BODY_BYTES, DEFAULT_MAX_BODY_BYTES);
        UUID runId = parseRunId(context.getParameter(PARAM_RUN_ID, ""));

        this.capture = new SampleCapture(defaultExtractors(), maxBodyBytes);
        this.sink = new WsSink(serverUrl, runId);
        this.sink.open();
        LOG.info("Wireglass backend listener streaming to {}", serverUrl);
    }

    @Override
    public void handleSampleResults(List<SampleResult> sampleResults, BackendListenerContext context) {
        if (sink == null || capture == null) {
            return;
        }
        for (SampleResult result : sampleResults) {
            capture.capture(result, sink);
        }
    }

    @Override
    public void teardownTest(BackendListenerContext context) throws Exception {
        if (sink != null) {
            sink.close();
        }
    }

    private static List<PacketExtractor> defaultExtractors() {
        return List.of(
                new HttpPacketExtractor(),
                new WebsocketPacketExtractor(),
                new TcpPacketExtractor());
    }

    private static UUID parseRunId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            LOG.warn("Ignoring invalid runId '{}': {}", value, e.toString());
            return null;
        }
    }
}
