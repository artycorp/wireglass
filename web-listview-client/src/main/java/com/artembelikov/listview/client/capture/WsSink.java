package com.artembelikov.listview.client.capture;

import com.artembelikov.listview.client.dto.CapturedPacket;
import com.artembelikov.listview.client.json.Json;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.enums.ReadyState;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Streams captured packets as JSON over a WebSocket connection to the web form's ingestion endpoint.
 * Used by the standalone jmeter-dsl client so a test running in any JVM can feed the browser UI.
 */
public class WsSink implements PacketSink {

    private static final Logger LOG = LoggerFactory.getLogger(WsSink.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final WebSocketClient client;

    public WsSink(String serverUrl) {
        try {
            this.client = new WebSocketClient(new URI(toWsUrl(serverUrl))) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    LOG.debug("WS capture stream connected to {}", getURI());
                }

                @Override
                public void onMessage(String message) {
                    // the ingestion endpoint is one-way; nothing expected back
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    LOG.debug("WS capture stream closed (code={}, remote={})", code, remote);
                }

                @Override
                public void onError(Exception ex) {
                    LOG.debug("WS capture stream error: {}", ex.toString());
                }
            };
        } catch (URISyntaxException e) {
            throw new RuntimeException("Invalid web-listview server URL: " + serverUrl, e);
        }
    }

    @Override
    public void open() {
        connect();
    }

    @Override
    public void publish(CapturedPacket packet) {
        if (!client.isOpen()) {
            // best-effort (re)connect; never block the sampler thread beyond the timeout
            connect();
        }
        if (client.isOpen()) {
            try {
                client.send(Json.write(packet));
            } catch (RuntimeException e) {
                LOG.debug("WS send failed: {}", e.toString());
            }
        } else if (LOG.isDebugEnabled()) {
            LOG.debug("Dropping packet {}: stream not open", packet.id());
        }
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (RuntimeException e) {
            LOG.debug("WS close failed: {}", e.toString());
        }
    }

    private void connect() {
        if (client.isOpen()) {
            return;
        }
        try {
            // A WebSocketClient is single-use: connectBlocking() works only on a fresh instance.
            // Once it has been opened (and possibly dropped, e.g. an oversized frame), the same
            // instance must be revived via reconnectBlocking(), otherwise every later send is lost.
            boolean connected = client.getReadyState() == ReadyState.NOT_YET_CONNECTED
                    ? client.connectBlocking(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    : client.reconnectBlocking();
            if (!connected) {
                LOG.warn("WS capture stream not connected to {} after {}", client.getURI(), CONNECT_TIMEOUT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String toWsUrl(String serverUrl) {
        String u = serverUrl == null ? "" : serverUrl.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        if (u.startsWith("https://")) {
            u = "wss://" + u.substring(8);
        } else if (u.startsWith("http://")) {
            u = "ws://" + u.substring(7);
        } else if (!u.startsWith("ws://") && !u.startsWith("wss://")) {
            u = "ws://" + u;
        }
        int schemeEnd = u.indexOf("://") + 3;
        if (u.indexOf('/', schemeEnd) < 0) {
            u = u + "/api/ingest";
        }
        return u;
    }
}
