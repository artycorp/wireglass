package com.artembelikov.listview.web;

import com.artembelikov.listview.client.dto.CapturedPacket;
import com.artembelikov.listview.store.PacketRepository;
import com.artembelikov.listview.store.RunRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/packets")
public class PacketsController {

    private final PacketRepository repository;
    private final RunRepository runs;

    @Autowired
    public PacketsController(PacketRepository repository, RunRepository runs) {
        this.repository = repository;
        this.runs = runs;
    }

    @GetMapping
    public List<CapturedPacket> recent(
            @RequestParam(required = false) UUID runId,
            @RequestParam(defaultValue = "200") int limit) {
        return repository.recent(runId, limit);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CapturedPacket> get(@PathVariable UUID id) {
        CapturedPacket packet = repository.get(id);
        return packet == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(packet);
    }

    @DeleteMapping
    public void clear() {
        repository.clear();
        runs.clear();
    }
}
