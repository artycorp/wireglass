package com.artembelikov.listview.capture;

import com.artembelikov.listview.client.capture.PacketSink;
import com.artembelikov.listview.client.dto.CapturedPacket;

/**
 * Sink used by the web app's own (in-process) test runner: packets go straight to the bus with no
 * serialization, feeding the SSE stream and the in-memory repository.
 */
public class InProcessSink implements PacketSink {

    private final PacketBus bus;

    public InProcessSink(PacketBus bus) {
        this.bus = bus;
    }

    @Override
    public void open() {
        // no-op: the bus is always live in-process
    }

    @Override
    public void publish(CapturedPacket packet) {
        bus.publish(packet);
    }

    @Override
    public void close() {
        // no-op
    }
}
