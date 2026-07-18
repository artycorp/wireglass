package com.wireglass.listview.capture;

import com.wireglass.listview.client.capture.PacketSink;
import com.wireglass.listview.client.dto.CapturedPacket;
import java.util.UUID;

/**
 * Sink used by the web app's own (in-process) test runner: packets go straight to the bus with no
 * serialization, feeding the SSE stream and the in-memory repository.
 */
public class InProcessSink implements PacketSink {

    private final PacketBus bus;
    private final UUID runId;

    public InProcessSink(PacketBus bus, UUID runId) {
        this.bus = bus;
        this.runId = runId;
    }

    @Override
    public void open() {
        // no-op: the bus is always live in-process
    }

    @Override
    public void publish(CapturedPacket packet) {
        bus.publish(packet.runId() == null ? packet.withRunId(runId) : packet);
    }

    @Override
    public void close() {
        // no-op
    }
}
