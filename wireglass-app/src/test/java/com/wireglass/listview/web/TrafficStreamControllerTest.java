package com.wireglass.listview.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.wireglass.listview.capture.PacketBus;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class TrafficStreamControllerTest {

    @Test
    void closeGracefullyIsIdempotent() {
        TrafficStreamController controller = new TrafficStreamController(mock(PacketBus.class));
        PacketBus.Subscription subscription = mock(PacketBus.Subscription.class);
        SseEmitter emitter = new SseEmitter();
        AtomicBoolean closed = new AtomicBoolean(false);

        controller.closeGracefully(subscription, emitter, closed);
        controller.closeGracefully(subscription, emitter, closed);

        assertThat(closed.get()).isTrue();
        verify(subscription, times(1)).close();
    }

    @Test
    void closeGracefullyToleratesANullSubscription() {
        TrafficStreamController controller = new TrafficStreamController(mock(PacketBus.class));
        SseEmitter emitter = new SseEmitter();
        AtomicBoolean closed = new AtomicBoolean(false);

        controller.closeGracefully(null, emitter, closed);

        assertThat(closed.get()).isTrue();
    }
}
