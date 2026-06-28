package com.artembelikov.listview.capture;

import com.artembelikov.listview.protocol.PacketExtractor;
import java.util.List;
import org.apache.jmeter.visualizers.SimpleDataWriter;
import us.abstracta.jmeter.javadsl.core.listeners.BaseListener;

public class TrafficCaptureListener extends BaseListener {

    private final PacketBus bus;
    private final List<PacketExtractor> extractors;
    private final int maxBodyBytes;

    public TrafficCaptureListener(PacketBus bus, List<PacketExtractor> extractors, int maxBodyBytes) {
        super("Traffic Capture Listener", SimpleDataWriter.class);
        this.bus = bus;
        this.extractors = extractors;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    public org.apache.jmeter.testelement.TestElement buildTestElement() {
        return new TrafficCapturingReporter(bus, extractors, maxBodyBytes);
    }
}
