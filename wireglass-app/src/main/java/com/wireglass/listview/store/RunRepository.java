package com.wireglass.listview.store;

import com.wireglass.listview.dto.RunSummary;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

@Component
public class RunRepository {

    private final Map<UUID, RunSummary> runs = new LinkedHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public void upsert(RunSummary run) {
        lock.lock();
        try {
            runs.put(run.id(), run);
        } finally {
            lock.unlock();
        }
    }

    public RunSummary get(UUID id) {
        lock.lock();
        try {
            return runs.get(id);
        } finally {
            lock.unlock();
        }
    }

    public List<RunSummary> recent() {
        lock.lock();
        try {
            List<RunSummary> snapshot = new ArrayList<>(runs.values());
            Collections.reverse(snapshot);
            return snapshot;
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            runs.clear();
        } finally {
            lock.unlock();
        }
    }
}
