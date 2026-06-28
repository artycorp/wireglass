package com.artembelikov.listview.config;

import com.artembelikov.listview.web.IngestionWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

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
}
