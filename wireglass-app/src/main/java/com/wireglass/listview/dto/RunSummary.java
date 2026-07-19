package com.wireglass.listview.dto;

import java.time.Instant;
import java.util.UUID;

public record RunSummary(
        UUID id,
        String source,
        String state,
        Instant startedAt,
        Instant finishedAt,
        String label,
        int threads,
        int iterations,
        int capturedSamples,
        int errorSamples,
        boolean restored) {

    public RunSummary asRestored() {
        return new RunSummary(
                id,
                source,
                state,
                startedAt,
                finishedAt,
                label,
                threads,
                iterations,
                capturedSamples,
                errorSamples,
                true);
    }
}
