package com.wireglass.listview.capture;

import com.wireglass.listview.client.capture.CapturingReporter;
import com.wireglass.listview.client.capture.PacketSink;
import org.apache.jmeter.testelement.TestElement;
import us.abstracta.jmeter.javadsl.core.listeners.BaseListener;

/**
 * In-process capture listener used when the web form launches its own test: packets flow directly to
 * the {@link PacketBus} (no network). External jmeter-dsl tests instead use
 * {@code TrafficCaptureClient} from the wireglass-client module.
 */
public class TrafficCaptureListener extends BaseListener {

    private final PacketSink sink;
    private final com.wireglass.listview.client.protocol.PacketExtractor[] extractors;
    private final int maxBodyBytes;

    public TrafficCaptureListener(PacketSink sink, int maxBodyBytes,
                                  com.wireglass.listview.client.protocol.PacketExtractor... extractors) {
        super("Traffic Capture Listener", org.apache.jmeter.visualizers.SimpleDataWriter.class);
        this.sink = sink;
        this.maxBodyBytes = maxBodyBytes;
        this.extractors = extractors;
    }

    @Override
    public TestElement buildTestElement() {
        return new CapturingReporter(sink, java.util.Arrays.asList(extractors), maxBodyBytes);
    }
}
