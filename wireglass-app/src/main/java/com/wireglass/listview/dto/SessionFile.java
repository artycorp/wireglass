package com.wireglass.listview.dto;

import com.wireglass.listview.client.dto.CapturedPacket;
import java.time.Instant;
import java.util.List;

public record SessionFile(
        int version,
        Instant exportedAt,
        List<RunSummary> runs,
        List<CapturedPacket> packets) {

    public static final int CURRENT_VERSION = 1;
}
