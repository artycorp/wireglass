package com.artembelikov.listview.client.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.artembelikov.listview.client.dto.CapturedPacket;
import com.artembelikov.listview.client.dto.PacketType;
import com.artembelikov.listview.client.protocol.PacketExtractor;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;

class CapturingReporterTest {

    private static class RecordingSink implements PacketSink {
        final List<String> publishedLabels = new ArrayList<>();

        @Override
        public void open() {
        }

        @Override
        public void publish(CapturedPacket packet) {
            publishedLabels.add(packet.label());
        }

        @Override
        public void close() {
        }
    }

    private static class AnyResultExtractor implements PacketExtractor {
        @Override
        public PacketType supportedType() {
            return PacketType.HTTP;
        }

        @Override
        public boolean supports(SampleResult result) {
            return true;
        }

        @Override
        public CapturedPacket extract(SampleResult result, int maxBodyBytes) {
            return new CapturedPacket(
                    UUID.randomUUID(), null, PacketType.HTTP, OffsetDateTime.now(),
                    result.getThreadName(), result.getSampleLabel(), "GET", "http://example.test",
                    Map.of(), "", 200, "OK", Map.of(), "", "text/plain", false, false,
                    result.getTime(), result.getLatency(), result.getConnectTime(),
                    true, "", 1, 1);
        }
    }

    @Test
    void doesNotCaptureAParentResultThatHasSubResults() {
        SampleResult parent = new SampleResult();
        parent.setSampleLabel("transaction-parent");
        SampleResult child1 = new SampleResult();
        child1.setSampleLabel("login");
        SampleResult child2 = new SampleResult();
        child2.setSampleLabel("orders");
        parent.addSubResult(child1, false);
        parent.addSubResult(child2, false);

        RecordingSink sink = new RecordingSink();
        CapturingReporter reporter = new CapturingReporter(sink, List.of(new AnyResultExtractor()), 1024);
        reporter.sampleOccurred(new org.apache.jmeter.samplers.SampleEvent(parent, "thread-group"));

        assertEquals(2, sink.publishedLabels.size());
        assertTrue(sink.publishedLabels.contains("login"));
        assertTrue(sink.publishedLabels.contains("orders"));
        assertTrue(!sink.publishedLabels.contains("transaction-parent"));
    }

    @Test
    void capturesALeafResultWithNoSubResults() {
        SampleResult leaf = new SampleResult();
        leaf.setSampleLabel("single-request");

        RecordingSink sink = new RecordingSink();
        CapturingReporter reporter = new CapturingReporter(sink, List.of(new AnyResultExtractor()), 1024);
        reporter.sampleOccurred(new org.apache.jmeter.samplers.SampleEvent(leaf, "thread-group"));

        assertEquals(List.of("single-request"), sink.publishedLabels);
    }
}
