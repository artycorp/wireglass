package com.artembelikov.listview.web;

import com.artembelikov.listview.capture.PacketBus;
import com.artembelikov.listview.client.dto.CapturedPacket;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/traffic")
public class TrafficStreamController {

    private static final Logger LOG = LoggerFactory.getLogger(TrafficStreamController.class);
    private static final long SSE_TIMEOUT = 0L; // never expire; controlled by client

    private final PacketBus bus;

    @Autowired
    public TrafficStreamController(PacketBus bus) {
        this.bus = bus;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        AtomicReference<PacketBus.Subscription> subRef = new AtomicReference<>();
        PacketBus.Subscription subscription = bus.subscribe(packet -> {
            try {
                emitter.send(SseEmitter.event()
                        .name("packet")
                        .data(packet, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                // Client went away (page reload / navigate / EventSource reconnect). Stop sending
                // and finish quietly. NOTE: completeWithError() would trigger an error dispatch on
                // the already-broken connection and make Tomcat log a noisy "Cannot start async:
                // [ERROR]" stack trace, so we unsubscribe and complete() normally instead.
                PacketBus.Subscription s = subRef.get();
                if (s != null) {
                    s.close();
                }
                LOG.debug("SSE client disconnected, closing stream: {}", e.toString());
                try {
                    emitter.complete();
                } catch (RuntimeException ignored) {
                    // already torn down
                }
            }
        });
        subRef.set(subscription);
        emitter.onCompletion(subscription::close);
        emitter.onTimeout(() -> {
            subscription.close();
            emitter.complete();
        });
        emitter.onError(t -> {
            LOG.debug("SSE stream error: {}", t.toString());
            subscription.close();
        });
        return emitter;
    }
}
