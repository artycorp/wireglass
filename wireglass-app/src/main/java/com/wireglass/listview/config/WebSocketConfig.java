package com.wireglass.listview.config;

import com.wireglass.listview.web.IngestionWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    // A captured packet carries a response body capped at max-body-bytes (256 KiB by default), which
    // JSON-serializes to ~1.33x as base64 plus headers. Tomcat's default text buffer is only 8 KiB,
    // so a single bulky packet would otherwise overflow it and the container would close the stream
    // (1009), losing every subsequent packet. 1 MiB comfortably covers a maxed-out packet.
    private static final int MAX_PACKET_MESSAGE_BYTES = 1024 * 1024;

    private final IngestionWebSocketHandler ingestionHandler;

    @Autowired
    public WebSocketConfig(IngestionWebSocketHandler ingestionHandler) {
        this.ingestionHandler = ingestionHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // External jmeter-java-dsl clients stream captured packets here.
        registry.addHandler(ingestionHandler, "/api/ingest").setAllowedOriginPatterns("*");
    }

    /** Raises the WebSocket engine's per-message buffers so bulky captured packets are not rejected. */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_PACKET_MESSAGE_BYTES);
        container.setMaxBinaryMessageBufferSize(MAX_PACKET_MESSAGE_BYTES);
        return container;
    }
}
