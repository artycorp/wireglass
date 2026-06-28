package com.artembelikov.listview.web;

import com.artembelikov.listview.capture.PacketBus;
import com.artembelikov.listview.client.dto.CapturedPacket;
import com.artembelikov.listview.client.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Receives captured packets streamed by external jmeter-java-dsl clients (each message is a
 * {@link CapturedPacket} JSON) and republishes them on the bus so they show up in the browser list
 * view and SSE stream exactly like packets from the in-process runner.
 */
@Component
public class IngestionWebSocketHandler extends TextWebSocketHandler {

    private static final Logger LOG = LoggerFactory.getLogger(IngestionWebSocketHandler.class);

    private final PacketBus bus;

    @Autowired
    public IngestionWebSocketHandler(PacketBus bus) {
        this.bus = bus;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        LOG.debug("ingestion client connected: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        if (payload == null || payload.isBlank()) {
            return;
        }
        try {
            CapturedPacket packet = Json.readPacket(payload);
            bus.publish(packet);
        } catch (RuntimeException e) {
            LOG.warn("Ignoring malformed ingestion packet: {}", e.toString());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        LOG.debug("ingestion transport error for {}: {}", session.getId(), exception.toString());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        LOG.debug("ingestion client disconnected: {} ({})", session.getId(), status);
    }
}
