package com.artembelikov.listview.capture;

import com.artembelikov.listview.client.protocol.HttpPacketExtractor;
import com.artembelikov.listview.client.protocol.PacketExtractor;
import com.artembelikov.listview.client.protocol.TcpPacketExtractor;
import com.artembelikov.listview.client.protocol.WebsocketPacketExtractor;
import com.artembelikov.listview.config.ListViewProperties;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class TrafficCaptureListenerFactory {

    private final PacketBus bus;
    private final ListViewProperties props;

    @Autowired
    public TrafficCaptureListenerFactory(PacketBus bus, ListViewProperties props) {
        this.bus = bus;
        this.props = props;
    }

    public TrafficCaptureListener newListener(UUID runId) {
        return new TrafficCaptureListener(new InProcessSink(bus, runId), props.getMaxBodyBytes(),
                orderedExtractors());
    }

    /**
     * HTTP first, then WebSocket, TCP last (catch-all). Order matters because
     * {@code TcpPacketExtractor.supports()} always returns true.
     */
    private static PacketExtractor[] orderedExtractors() {
        return new PacketExtractor[]{
                new HttpPacketExtractor(),
                new WebsocketPacketExtractor(),
                new TcpPacketExtractor()
        };
    }
}
