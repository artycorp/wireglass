package com.wireglass.listview.web;

import com.wireglass.listview.client.dto.CapturedPacket;
import com.wireglass.listview.dto.RunSummary;
import com.wireglass.listview.dto.SessionFile;
import com.wireglass.listview.dto.SessionImportResult;
import com.wireglass.listview.store.PacketRepository;
import com.wireglass.listview.store.RunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/session")
public class SessionController {

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneId.systemDefault());

    private final PacketRepository packets;
    private final RunRepository runs;
    private final Clock clock;

    @Autowired
    public SessionController(PacketRepository packets, RunRepository runs, Clock clock) {
        this.packets = packets;
        this.runs = runs;
        this.clock = clock;
    }

    @GetMapping("/export")
    public ResponseEntity<SessionFile> export() {
        Instant now = Instant.now(clock);
        SessionFile session = new SessionFile(
                SessionFile.CURRENT_VERSION, now, runs.recent(), packets.snapshot());
        String filename = "wireglass-session-" + FILE_STAMP.format(now) + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(session);
    }

    @PostMapping("/import")
    public ResponseEntity<?> importSession(@RequestBody SessionFile session) {
        if (session == null || session.version() != SessionFile.CURRENT_VERSION) {
            return ResponseEntity.badRequest().body(new ImportError(
                    "unsupported session file version: expected " + SessionFile.CURRENT_VERSION
                            + ", got " + (session == null ? "none" : session.version())));
        }
        List<RunSummary> importedRuns = session.runs() == null ? List.of() : session.runs();
        List<CapturedPacket> importedPackets = session.packets() == null ? List.of() : session.packets();

        for (RunSummary run : importedRuns) {
            if (run != null && run.id() != null) {
                runs.upsert(run.asRestored());
            }
        }
        int added = packets.importAll(importedPackets);
        return ResponseEntity.ok(new SessionImportResult(
                importedRuns.size(), added, importedPackets.size() - added));
    }

    public record ImportError(String message) {
    }
}
