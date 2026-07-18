package com.wireglass.listview.capture;

import com.wireglass.listview.client.dto.CapturedPacket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.wireglass.listview.store.PacketRepository;

@Component
public class PacketBus {

    private final PacketRepository repository;
    private final List<Consumer<CapturedPacket>> subscribers = new CopyOnWriteArrayList<>();

    @Autowired
    public PacketBus(PacketRepository repository) {
        this.repository = repository;
    }

    public void publish(CapturedPacket packet) {
        repository.add(packet);
        for (Consumer<CapturedPacket> subscriber : subscribers) {
            try {
                subscriber.accept(packet);
            } catch (RuntimeException ignored) {
                // a slow/failing subscriber must not break capture or other subscribers
            }
        }
    }

    public Subscription subscribe(Consumer<CapturedPacket> subscriber) {
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }

    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
