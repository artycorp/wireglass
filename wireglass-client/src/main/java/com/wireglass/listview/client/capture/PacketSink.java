package com.wireglass.listview.client.capture;

import com.wireglass.listview.client.dto.CapturedPacket;

/**
 * Transport-agnostic destination for captured packets. Implementations:
 * <ul>
 *   <li>in-process (web app): publish straight to the {@code PacketBus}</li>
 *   <li>remote (jmeter-dsl client): serialize and stream over WebSocket</li>
 * </ul>
 */
public interface PacketSink {

    void open();

    void publish(CapturedPacket packet);

    void close();
}
