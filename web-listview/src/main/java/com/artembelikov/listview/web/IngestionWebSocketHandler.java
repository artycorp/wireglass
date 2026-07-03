package com.artembelikov.listview.web;

import com.artembelikov.listview.capture.PacketBus;
import com.artembelikov.listview.client.dto.CapturedPacket;
import com.artembelikov.listview.client.json.Json;
import com.artembelikov.listview.dto.RunSummary;
import com.artembelikov.listview.store.RunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
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
    private static final String SESSION_RUN_IDS = "runIds";
    private static final String SESSION_FALLBACK_RUN_ID = "fallbackRunId";

    private final PacketBus bus;
    private final RunRepository runs;
    private final Clock clock;

    @Autowired
    public IngestionWebSocketHandler(PacketBus bus, RunRepository runs, Clock clock) {
        this.bus = bus;
        this.runs = runs;
        this.clock = clock;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.getAttributes().put(SESSION_RUN_IDS, new LinkedHashSet<UUID>());
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
            UUID runId = resolveRunId(session, packet);
            packet = packet.runId() == null ? packet.withRunId(runId) : packet;
            touchRun(packet);
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
        Object value = session.getAttributes().get(SESSION_RUN_IDS);
        if (value instanceof Set<?> ids) {
            for (Object id : ids) {
                if (id instanceof UUID uuid) {
                    finishRun(uuid);
                }
            }
        }
        LOG.debug("ingestion client disconnected: {} ({})", session.getId(), status);
    }

    private UUID resolveRunId(WebSocketSession session, CapturedPacket packet) {
        if (packet.runId() != null) {
            rememberRunId(session, packet.runId());
            return packet.runId();
        }
        UUID fallback = (UUID) session.getAttributes().get(SESSION_FALLBACK_RUN_ID);
        if (fallback == null) {
            fallback = UUID.randomUUID();
            session.getAttributes().put(SESSION_FALLBACK_RUN_ID, fallback);
        }
        rememberRunId(session, fallback);
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private void rememberRunId(WebSocketSession session, UUID runId) {
        ((Set<UUID>) session.getAttributes().computeIfAbsent(SESSION_RUN_IDS, key -> new LinkedHashSet<UUID>()))
                .add(runId);
    }

    private void touchRun(CapturedPacket packet) {
        RunSummary existing = runs.get(packet.runId());
        Instant startedAt = existing == null ? Instant.now(clock) : existing.startedAt();
        int captured = existing == null ? 0 : existing.capturedSamples();
        int errors = existing == null ? 0 : existing.errorSamples();
        runs.upsert(new RunSummary(
                packet.runId(),
                "external",
                "RUNNING",
                startedAt,
                null,
                packet.url(),
                packet.groupThreads(),
                0,
                captured + 1,
                errors + (packet.success() ? 0 : 1)));
    }

    private void finishRun(UUID runId) {
        RunSummary existing = runs.get(runId);
        if (existing == null) {
            return;
        }
        runs.upsert(new RunSummary(
                existing.id(),
                existing.source(),
                "FINISHED",
                existing.startedAt(),
                Instant.now(clock),
                existing.label(),
                existing.threads(),
                existing.iterations(),
                existing.capturedSamples(),
                existing.errorSamples()));
    }
}
