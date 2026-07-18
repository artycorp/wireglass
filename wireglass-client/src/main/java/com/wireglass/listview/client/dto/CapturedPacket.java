package com.wireglass.listview.client.dto;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record CapturedPacket(
        UUID id,
        UUID runId,
        PacketType type,
        OffsetDateTime timestamp,
        String threadName,
        String label,
        String method,
        String url,
        Map<String, String> requestHeaders,
        String requestBody,
        int status,
        String statusMessage,
        Map<String, String> responseHeaders,
        String responseBody,
        String responseContentType,
        boolean bodyBinary,
        boolean bodyTruncated,
        long elapsedMs,
        long latencyMs,
        long connectMs,
        boolean success,
        String failureMessage,
        int groupThreads,
        int allThreads) {

    public CapturedPacket withRunId(UUID runId) {
        return new CapturedPacket(
                id,
                runId,
                type,
                timestamp,
                threadName,
                label,
                method,
                url,
                requestHeaders,
                requestBody,
                status,
                statusMessage,
                responseHeaders,
                responseBody,
                responseContentType,
                bodyBinary,
                bodyTruncated,
                elapsedMs,
                latencyMs,
                connectMs,
                success,
                failureMessage,
                groupThreads,
                allThreads);
    }
}
