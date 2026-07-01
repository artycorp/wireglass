package com.artembelikov.listview.store;

import com.artembelikov.listview.client.dto.CapturedPacket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PacketRepository {

    private final int capacity;
    private final Deque<CapturedPacket> ring = new ArrayDeque<>();
    private final Map<UUID, CapturedPacket> index = new LinkedHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    @Autowired
    public PacketRepository(com.artembelikov.listview.config.ListViewProperties props) {
        this.capacity = Math.max(1, props.getRingBufferSize());
    }

    public void add(CapturedPacket packet) {
        lock.lock();
        try {
            ring.addLast(packet);
            index.put(packet.id(), packet);
            while (ring.size() > capacity) {
                CapturedPacket evicted = ring.removeFirst();
                index.remove(evicted.id());
            }
        } finally {
            lock.unlock();
        }
    }

    public CapturedPacket get(UUID id) {
        lock.lock();
        try {
            return index.get(id);
        } finally {
            lock.unlock();
        }
    }

    public List<CapturedPacket> recent(int limit) {
        return recent(null, limit);
    }

    public List<CapturedPacket> recent(UUID runId, int limit) {
        int n = Math.max(0, limit);
        lock.lock();
        try {
            List<CapturedPacket> snapshot = new ArrayList<>(ring);
            if (runId != null) {
                snapshot.removeIf(packet -> !runId.equals(packet.runId()));
            }
            if (n == 0 || n >= snapshot.size()) {
                return snapshot;
            }
            return snapshot.subList(snapshot.size() - n, snapshot.size());
        } finally {
            lock.unlock();
        }
    }

    public int count() {
        lock.lock();
        try {
            return ring.size();
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            ring.clear();
            index.clear();
        } finally {
            lock.unlock();
        }
    }

    public List<CapturedPacket> snapshot() {
        lock.lock();
        try {
            return Collections.unmodifiableList(new ArrayList<>(ring));
        } finally {
            lock.unlock();
        }
    }
}
