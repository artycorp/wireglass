package com.artembelikov.listview.web;

import com.artembelikov.listview.capture.TestRunService;
import com.artembelikov.listview.dto.RunRequest;
import com.artembelikov.listview.dto.RunStatus;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runs")
public class RunApiController {

    private final TestRunService runService;

    @Autowired
    public RunApiController(TestRunService runService) {
        this.runService = runService;
    }

    @PostMapping
    public RunStatus start(@RequestBody RunRequest request) {
        return runService.start(request);
    }

    @PostMapping("/demo")
    public RunStatus startDemo() {
        return runService.startDemo();
    }

    @PostMapping("/{id}/stop")
    public ResponseEntity<Void> stop(@PathVariable UUID id) {
        return runService.stop(id) ? ResponseEntity.accepted().build()
                : ResponseEntity.notFound().build();
    }

    @GetMapping("/{id}")
    public ResponseEntity<RunStatus> status(@PathVariable UUID id) {
        RunStatus status = runService.status(id);
        return status == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(status);
    }
}
