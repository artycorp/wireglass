package com.artembelikov.listview.dto;

import java.time.Instant;
import java.util.UUID;

public record RunStatus(
        UUID id,
        String state,
        Instant startedAt,
        Instant finishedAt,
        String label,
        int threads,
        int iterations,
        int capturedSamples,
        int errorSamples) {
}
